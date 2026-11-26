package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.status.StatusLogger;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Одна запись в лог на входящий запрос: метод, путь, статус, время, размеры и разрешённые заголовки.
 * <p>
 * Тела запроса и ответа (замаскированные, не больше max-body-bytes) попадают в запись всегда при 4xx, 5xx и
 * исключении, а у успешных обменов — только при {@code bodies=ALWAYS}. Уровень записи: успех — INFO, 4xx — WARN,
 * 5xx и исключение — ERROR.
 * <p>
 * Тела не буферизуются целиком: начало тела запроса читается заранее и отдаётся приложению вместе с остальным
 * телом ({@link CapturingRequestWrapper}), ответ сразу уходит клиенту, а для лога копируются только первые байты.
 * <p>
 * Если ответ — ошибка без тела (sendError: 404, 405, 415 из Spring; исключение), тело пишет страница ошибки
 * Spring Boot уже после цепочки. Тогда запись откладывается до прохода ERROR и включает тело страницы ошибки;
 * если прохода ERROR не будет, запись пишется по завершении запроса ({@link #completeRequest}).
 */
public class HttpServerLoggingFilter extends OncePerRequestFilter {

    static final String LOGGER_NAME = "observability.http.in";
    private static final String PENDING = HttpServerLoggingFilter.class.getName() + ".PENDING";
    private static final int CLIENT_ERROR = 400;
    private static final int SERVER_ERROR = 500;

    private final HttpLogSettings settings;
    private final HttpLogSink sink;

    public HttpServerLoggingFilter(final HttpLogSettings settings) {
        this(settings, HttpLogSink.logger(LOGGER_NAME));
    }

    HttpServerLoggingFilter(final HttpLogSettings settings, final HttpLogSink sink) {
        this.settings = settings;
        this.sink = sink;
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        // при уровне WARN фильтр продолжает работать, чтобы записать 4xx, 5xx и исключения
        return !sink.isEnabled(Level.WARN) || settings.isExcluded(pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            // страница ошибки — не отдельный обмен: она только дописывает тело в отложенную запись
            errorPage(request, response, chain);
            return;
        }
        long start = System.nanoTime();
        // статус ещё неизвестен, а тело ошибки нужно всегда, поэтому начало тел копится у каждого обмена
        // (не больше max-body-bytes) и попадает в запись при ошибке или при bodies=ALWAYS
        CapturingRequestWrapper capturedRequest = new CapturingRequestWrapper(request, settings.maxBodyBytes());
        CapturingResponseWrapper capturedResponse = new CapturingResponseWrapper(response, settings.maxBodyBytes());
        capturedRequest.prefetch();
        Exception failure = null;
        try {
            chain.doFilter(capturedRequest, capturedResponse);
        } catch (IOException | ServletException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            if (failure == null && request.isAsyncStarted()) {
                // DeferredResult, Callable, CompletableFuture: ответ ещё не готов, запись — по завершении обработки
                request.getAsyncContext().addListener(
                    new CompletionListener(request, response, capturedRequest, capturedResponse, start));
            } else {
                capturedResponse.flushWriter();
                if (awaitsErrorPage(request, response, capturedResponse, failure)) {
                    request.setAttribute(PENDING,
                        new Pending(request, response, capturedRequest, capturedResponse, failure, start));
                } else {
                    log(request, response, capturedRequest, capturedResponse, failure, start);
                }
            }
        }
    }

    private void finish(final HttpServletRequest request, final HttpServletResponse response,
                        final CapturingRequestWrapper capturedRequest, final CapturingResponseWrapper capturedResponse,
                        final Exception failure, final long start) {
        capturedResponse.flushWriter();
        log(request, response, capturedRequest, capturedResponse, failure, start);
    }

    /**
     * Ошибка без тела, которое ещё напишет страница ошибки контейнера: sendError (после него Tomcat считает ответ
     * зафиксированным, но тело ещё не записано) или исключение до записи ответа.
     */
    private static boolean awaitsErrorPage(final HttpServletRequest request, final HttpServletResponse response,
                                           final CapturingResponseWrapper capturedResponse, final Exception failure) {
        return (capturedResponse.errorSent() || (failure != null && !response.isCommitted()))
            && capturedResponse.capture().total() == 0 && request.getDispatcherType() == DispatcherType.REQUEST;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * Проход ERROR (страница ошибки Spring Boot): если запись отложена, тело страницы ошибки копируется и запись
     * пишется с ним. Сам проход ERROR отдельной записи не даёт.
     */
    @Override
    protected void doFilterNestedErrorDispatch(final HttpServletRequest request, final HttpServletResponse response,
                                               final FilterChain chain) throws ServletException, IOException {
        errorPage(request, response, chain);
    }

    private void errorPage(final HttpServletRequest request, final HttpServletResponse response,
                           final FilterChain chain) throws ServletException, IOException {
        Object pending = request.getAttribute(PENDING);
        if (!(pending instanceof Pending record)) {
            chain.doFilter(request, response);
            return;
        }
        CapturingResponseWrapper errorPage = new CapturingResponseWrapper(response, settings.maxBodyBytes());
        try {
            chain.doFilter(request, errorPage);
        } finally {
            errorPage.flushWriter();
            record.complete(errorPage);
        }
    }

    /**
     * Пишет отложенную запись, если прохода ERROR не было (страница ошибки не настроена или контейнер отдал свою).
     * Вызывается слушателем завершения запроса, который регистрирует библиотека.
     */
    public static void completeRequest(final ServletRequest request) {
        Object pending = request.getAttribute(PENDING);
        if (pending instanceof Pending record) {
            record.complete(null);
        }
    }

    /** Отложенная запись об ошибке: всё, что нужно для неё, и MDC запроса (к проходу ERROR он уже очищен). */
    private final class Pending {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final CapturingRequestWrapper capturedRequest;
        private final CapturingResponseWrapper capturedResponse;
        private final Exception failure;
        private final long start;
        private final Map<String, String> context = ThreadContext.getImmutableContext();
        private final AtomicBoolean done = new AtomicBoolean();

        Pending(final HttpServletRequest request, final HttpServletResponse response,
                final CapturingRequestWrapper capturedRequest, final CapturingResponseWrapper capturedResponse,
                final Exception failure, final long start) {
            this.request = request;
            this.response = response;
            this.capturedRequest = capturedRequest;
            this.capturedResponse = capturedResponse;
            this.failure = failure;
            this.start = start;
        }

        void complete(final CapturingResponseWrapper errorPage) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            request.removeAttribute(PENDING);
            withContext(context, () -> log(request, response, capturedRequest,
                errorPage != null ? errorPage : capturedResponse, failure, start));
        }
    }

    /** Пишет запись с MDC исходного запроса: на другом потоке или после очистки MDC его уже нет. */
    private static void withContext(final Map<String, String> context, final Runnable action) {
        Map<String, String> current = ThreadContext.getImmutableContext();
        try {
            ThreadContext.clearMap();
            ThreadContext.putAll(context);
            action.run();
        } finally {
            ThreadContext.clearMap();
            ThreadContext.putAll(current);
        }
    }

    /** Путь без context-path: шаблоны exclude-paths и body-paths пишутся относительно приложения. */
    static String pathWithinApplication(final HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return uri != null && context != null && !context.isEmpty() && uri.startsWith(context)
            ? uri.substring(context.length()) : uri;
    }

    /** Пишет запись об асинхронном запросе один раз — при завершении, ошибке или тайм-ауте. */
    private final class CompletionListener implements AsyncListener {

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        private final CapturingRequestWrapper capturedRequest;
        private final CapturingResponseWrapper capturedResponse;
        private final long start;
        private final Map<String, String> context = ThreadContext.getImmutableContext();
        private final AtomicBoolean done = new AtomicBoolean();

        CompletionListener(final HttpServletRequest request, final HttpServletResponse response,
                           final CapturingRequestWrapper capturedRequest,
                           final CapturingResponseWrapper capturedResponse, final long start) {
            this.request = request;
            this.response = response;
            this.capturedRequest = capturedRequest;
            this.capturedResponse = capturedResponse;
            this.start = start;
        }

        @Override
        public void onComplete(final AsyncEvent event) {
            complete(null);
        }

        @Override
        public void onTimeout(final AsyncEvent event) {
            complete(new IllegalStateException("async request timed out"));
        }

        @Override
        public void onError(final AsyncEvent event) {
            Throwable error = event.getThrowable();
            complete(error instanceof Exception e ? e : new IllegalStateException(String.valueOf(error)));
        }

        @Override
        public void onStartAsync(final AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }

        private void complete(final Exception failure) {
            if (done.compareAndSet(false, true)) {
                withContext(context, () -> finish(request, response, capturedRequest, capturedResponse, failure, start));
            }
        }
    }

    private void log(final HttpServletRequest request, final HttpServletResponse response,
                     final CapturingRequestWrapper capturedRequest, final CapturingResponseWrapper capturedResponse,
                     final Exception failure, final long start) {
        try {
            Level level = levelOf(failure != null, response.getStatus());
            if (sink.isEnabled(level)) {
                sink.log(level, buildMessage(request, response, capturedRequest, capturedResponse,
                    failure, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            }
        } catch (RuntimeException e) {
            // сбой логирования не должен менять ответ клиенту
            StatusLogger.getLogger().warn("HTTP in logging failed", e);
        }
    }

    static Level levelOf(final boolean failed, final int status) {
        Level level = Level.INFO;
        if (failed || status >= SERVER_ERROR) {
            level = Level.ERROR;
        } else if (status >= CLIENT_ERROR) {
            level = Level.WARN;
        }
        return level;
    }

    private HttpLogMessage buildMessage(final HttpServletRequest request, final HttpServletResponse response,
                                        final CapturingRequestWrapper capturedRequest,
                                        final CapturingResponseWrapper capturedResponse,
                                        final Exception failure, final long durationMs) {
        int status = failure != null ? SERVER_ERROR : response.getStatus();
        String outcome = failure != null ? "FAILED (" + failure.getClass().getSimpleName() + ")" : String.valueOf(status);
        // в пути бывает номер карты (например, recipient-info/{processingKey}), поэтому путь маскируется
        String path = LogTextMasker.maskPath(pathWithinApplication(request));
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String summary = "HTTP in " + request.getMethod() + " " + path + " -> " + outcome + " in " + durationMs + " ms";

        // имена полей — OpenTelemetry semantic conventions (HTTP server); свои поля — под префиксом uz.kapitalbank.
        String method = request.getMethod();
        String errorType = failure != null ? failure.getClass().getName()
            : status >= SERVER_ERROR ? String.valueOf(status) : null;
        HttpLogMessage message = new HttpLogMessage(summary)
            .field("http.request.method", HttpLogSettings.otelMethod(method))
            .field("http.request.method_original", HttpLogSettings.knownMethod(method) ? null : method)
            .field("url.scheme", Forwarded.scheme(request))
            .field("url.path", path)
            .field("url.query", HttpLogSettings.redactQuery(request.getQueryString()))
            .field("http.route", route)
            .field("server.address", Forwarded.host(request))
            .field("server.port", Forwarded.port(request))
            .field("client.address", Forwarded.client(request))
            .field("network.protocol.version", HttpLogSettings.protocolVersion(request.getProtocol()))
            .field("user_agent.original", userAgent(request))
            .field("http.response.status_code", status)
            .field(HttpLogFields.DURATION_MS, durationMs)
            // error.type по OpenTelemetry: класс исключения, а без него у 5xx — код статуса строкой
            .field("error.type", errorType);
        // User-Agent уже есть в user_agent.original: в http.request.header.* не дублируется
        List<String> headerNames = new ArrayList<>(Collections.list(request.getHeaderNames()));
        headerNames.removeIf("User-Agent"::equalsIgnoreCase);
        settings.addRequestHeaders(message, headerNames, name -> Collections.list(request.getHeaders(name)));
        // прежний формат для Kafka-топика (rc1/rc2), порядок полей — как раньше
        message.legacy("http.direction", "in")
            .legacy("http.request.method", method)
            .legacy("url.path", path)
            .legacy("http.route", route)
            .legacy("http.response.status_code", status)
            .legacy("event.duration_ms", durationMs)
            .legacy("http.request.headers", settings.renderHeaders(
                Collections.list(request.getHeaderNames()), request::getHeader))
            .legacy("error.type", failure == null ? null : failure.getClass().getName());

        boolean includeBodies = settings.includeBodies(status, failure != null, pathWithinApplication(request));
        if (includeBodies) {
            String content;
            long bytes = capturedRequest.totalBytes();
            if (capturedRequest.isFormPost()) {
                Map<String, String[]> form = capturedRequest.formForLog();
                content = form != null ? settings.renderForm(form)
                    : "[" + request.getContentType() + ", " + bytes + " bytes]";
            } else if (capturedRequest.isMultipart()) {
                String parts = capturedRequest.partsForLog();
                content = parts != null ? parts : "[" + request.getContentType() + ", " + bytes + " bytes, not parsed]";
            } else {
                content = settings.renderBody(capturedRequest.capture(), request.getContentType());
            }
            long declared = request.getContentLengthLong();
            // http.request.body.size — байты по сети (Content-Length); сколько прочитало приложение — своё поле
            message.field("http.request.body.size", declared >= 0 ? declared : null)
                .field("http.request.body.content", content)
                .field(HttpLogFields.REQUEST_BODY_APP_SIZE, bytes > 0 ? bytes : null);
            message.legacy("http.request.body.bytes", bytes > 0 ? bytes : null)
                .legacy("http.request.body.content", content);
        }
        if (includeBodies) {
            String responseBody = settings.renderBody(capturedResponse.capture(), response.getContentType());
            long total = capturedResponse.capture().total();
            long declared = capturedResponse.declaredLength();
            message.field("http.response.body.size", declared >= 0 ? declared : null)
                .field("http.response.body.content", responseBody)
                .field(HttpLogFields.RESPONSE_BODY_APP_SIZE, total)
                .field(HttpLogFields.ERROR_CODE, status >= CLIENT_ERROR ? HttpLogSettings.errorCode(responseBody) : null);
            message.legacy("http.response.body.bytes", total)
                .legacy("http.response.body.content", responseBody);
        }
        return message;
    }

    private static String userAgent(final HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        return agent == null || agent.isEmpty() ? null : LogTextMasker.maskOrPlaceholder(agent);
    }
}
