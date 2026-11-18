package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.status.StatusLogger;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Одна запись в лог на входящий запрос: метод, путь, статус, время, размеры,
 * разрешённые заголовки и (для путей из body-paths) замаскированные начала тел.
 * <p>
 * Тела не буферизуются целиком: запрос читается приложением напрямую, ответ сразу уходит клиенту,
 * а для лога копируются только первые max-body-bytes байт.
 */
public class HttpServerLoggingFilter extends OncePerRequestFilter {

    static final String LOGGER_NAME = "observability.http.in";
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
        // при уровне WARN фильтр продолжает работать, чтобы записать ошибки и 5xx
        return !sink.isEnabled(Level.WARN) || settings.isExcluded(pathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        // при ON_ERROR статус ещё неизвестен, поэтому начало тела копится заранее (не больше max-body-bytes),
        // а в запись попадает, только если обмен закончился ошибкой
        boolean bodies = sink.isEnabled(settings.bodyCaptureLevel()) && settings.logBodies(pathWithinApplication(request));
        CapturingRequestWrapper capturedRequest = bodies
            ? new CapturingRequestWrapper(request, settings.maxBodyBytes()) : null;
        CapturingResponseWrapper capturedResponse = bodies
            ? new CapturingResponseWrapper(response, settings.maxBodyBytes()) : null;
        Exception failure = null;
        try {
            chain.doFilter(capturedRequest != null ? capturedRequest : request,
                capturedResponse != null ? capturedResponse : response);
        } catch (IOException | ServletException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            if (failure == null && request.isAsyncStarted()) {
                // DeferredResult, Callable, CompletableFuture: ответ ещё не готов, запись — по завершении обработки
                request.getAsyncContext().addListener(
                    new CompletionListener(request, response, capturedRequest, capturedResponse, start));
            } else {
                finish(request, response, capturedRequest, capturedResponse, failure, start);
            }
        }
    }

    private void finish(final HttpServletRequest request, final HttpServletResponse response,
                        final CapturingRequestWrapper capturedRequest, final CapturingResponseWrapper capturedResponse,
                        final Exception failure, final long start) {
        if (capturedResponse != null) {
            capturedResponse.flushWriter();
        }
        if (capturedRequest != null && settings.includeBodies(response.getStatus(), failure != null)) {
            capturedRequest.captureUnread();
        }
        log(request, response, capturedRequest, capturedResponse, failure, start);
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
                finish(request, response, capturedRequest, capturedResponse, failure, start);
            }
        }
    }

    private void log(final HttpServletRequest request, final HttpServletResponse response,
                     final CapturingRequestWrapper capturedRequest, final CapturingResponseWrapper capturedResponse,
                     final Exception failure, final long start) {
        try {
            Level level = levelOf(failure, response);
            if (sink.isEnabled(level)) {
                sink.log(level, buildMessage(request, response, capturedRequest, capturedResponse,
                    failure, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            }
        } catch (RuntimeException e) {
            // сбой логирования не должен менять ответ клиенту
            StatusLogger.getLogger().warn("HTTP in logging failed", e);
        }
    }

    private static Level levelOf(final Exception failure, final HttpServletResponse response) {
        return failure != null || response.getStatus() >= SERVER_ERROR ? Level.WARN : Level.INFO;
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

        HttpLogMessage message = new HttpLogMessage(summary)
            .field("http.direction", "in")
            .field("http.request.method", request.getMethod())
            .field("url.path", path)
            .field("http.route", route)
            .field("http.response.status_code", status)
            .field("event.duration_ms", durationMs)
            .field("http.request.headers", settings.renderHeaders(
                Collections.list(request.getHeaderNames()), request::getHeader))
            .field("error.type", failure == null ? null : failure.getClass().getName());

        boolean includeBodies = settings.includeBodies(status, failure != null);
        if (capturedRequest != null && includeBodies) {
            message.field("http.request.body.bytes", capturedRequest.capture().total())
                .field("http.request.body.content",
                    settings.renderBody(capturedRequest.capture(), request.getContentType()));
        }
        if (capturedResponse != null && includeBodies) {
            message.field("http.response.body.bytes", capturedResponse.capture().total())
                .field("http.response.body.content",
                    settings.renderBody(capturedResponse.capture(), response.getContentType()));
        }
        return message;
    }
}
