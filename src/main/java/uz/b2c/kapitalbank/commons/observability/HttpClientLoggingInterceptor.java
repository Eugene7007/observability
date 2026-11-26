package uz.b2c.kapitalbank.commons.observability;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.status.StatusLogger;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Одна запись в лог на исходящий вызов RestTemplate.
 * <p>
 * В отличие от прежнего ClientRequestInterceptor, тело ответа не вычитывается заранее: приложение читает его
 * как обычно, а интерцептор копирует первые байты по ходу чтения и пишет лог при закрытии ответа.
 * Поэтому RestTemplate больше не нужно оборачивать в BufferingClientHttpRequestFactory.
 */
public class HttpClientLoggingInterceptor implements ClientHttpRequestInterceptor, Ordered {

    private static final int CLIENT_ERROR = 400;
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    static final String LOGGER_NAME = "observability.http.out";

    private final HttpLogSettings settings;
    private final HttpLogSink sink;

    public HttpClientLoggingInterceptor(final HttpLogSettings settings) {
        this(settings, HttpLogSink.logger(LOGGER_NAME));
    }

    HttpClientLoggingInterceptor(final HttpLogSettings settings, final HttpLogSink sink) {
        this.settings = settings;
        this.sink = sink;
    }

    @Override
    public int getOrder() {
        // последним в цепочке: в лог попадают заголовки, уже добавленные другими интерцепторами
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
                                        final ClientHttpRequestExecution execution) throws IOException {
        String path = request.getURI().getPath();
        ClientHttpResponse result;
        // при уровне WARN интерцептор продолжает работать, чтобы записать ошибки и 5xx
        if (!sink.isEnabled(Level.WARN) || settings.isExcluded(path)) {
            result = execution.execute(request, body);
        } else {
            result = executeLogged(request, body, execution, path);
        }
        return result;
    }

    private ClientHttpResponse executeLogged(final HttpRequest request, final byte[] body,
                                             final ClientHttpRequestExecution execution,
                                             final String path) throws IOException {
        long start = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException e) {
            // ошибка вызова: тело запроса пишется всегда
            log(request, body, true, null, null, e, start);
            throw e;
        }
        int status = response.getStatusCode().value();
        ClientHttpResponse result = response;
        // статус уже известен: у успешного ответа без bodies=ALWAYS тело ответа даже не копируется
        boolean bodies = settings.includeBodies(status, false, path);
        if (bodies) {
            BodyCapture capture = new BodyCapture(settings.maxBodyBytes());
            String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
            result = new CapturingClientHttpResponse(response, capture,
                () -> log(request, body, true, status, new CapturedResponse(capture, contentType), null, start));
        } else {
            log(request, body, false, status, null, null, start);
        }
        return result;
    }

    /** Порт сервера: явный из URL или по умолчанию для схемы, как требует OpenTelemetry для server.port. */
    private static Integer serverPort(final URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        String scheme = uri.getScheme();
        return "https".equalsIgnoreCase(scheme) ? Integer.valueOf(HTTPS_PORT)
            : "http".equalsIgnoreCase(scheme) ? Integer.valueOf(HTTP_PORT) : null;
    }

    private record CapturedResponse(BodyCapture capture, String contentType) {
    }

    private void log(final HttpRequest request, final byte[] body, final boolean bodies, final Integer status,
                     final CapturedResponse response, final Exception failure, final long start) {
        try {
            Level level = HttpServerLoggingFilter.levelOf(failure != null, status == null ? 0 : status);
            if (sink.isEnabled(level)) {
                sink.log(level, buildMessage(request, body, bodies, status, response, failure,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            }
        } catch (RuntimeException e) {
            // вызывается и из close() ответа: сбой логирования не должен подменять результат вызова
            StatusLogger.getLogger().warn("HTTP out logging failed", e);
        }
    }

    private HttpLogMessage buildMessage(final HttpRequest request, final byte[] body, final boolean bodies,
                                        final Integer status, final CapturedResponse response,
                                        final Exception failure, final long durationMs) {
        URI uri = request.getURI();
        // query не логируется вовсе, номер карты в пути маскируется
        // без учётных данных из user@host и без query: в них часто токены и ПДн
        String target = uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort())
            + LogTextMasker.maskPath(uri.getRawPath());
        String outcome = failure != null ? "FAILED (" + failure.getClass().getSimpleName() + ")" : String.valueOf(status);
        String summary = "HTTP out " + request.getMethod() + " " + target + " -> " + outcome + " in " + durationMs + " ms";
        HttpHeaders headers = request.getHeaders();

        // имена полей — OpenTelemetry semantic conventions (HTTP client); свои поля — под префиксом uz.kapitalbank.
        HttpLogMessage message = new HttpLogMessage(summary)
            .field("http.request.method", request.getMethod())
            .field("url.full", target)
            .field("server.address", uri.getHost())
            .field("server.port", serverPort(uri))
            .field("http.response.status_code", status)
            .field(HttpLogFields.DURATION_MS, durationMs)
            // error.type по OpenTelemetry: класс исключения, а без него у 4xx и 5xx — код статуса строкой
            .field("error.type", failure != null ? failure.getClass().getName()
                : status != null && status >= CLIENT_ERROR ? String.valueOf(status) : null);
        settings.addRequestHeaders(message, headers.keySet(), headers::get);

        if (bodies) {
            message.field("http.request.body.size", body == null ? 0 : body.length)
                .field("http.request.body.content",
                    settings.renderBody(body, headers.getFirst(HttpHeaders.CONTENT_TYPE)));
            if (response != null) {
                String responseBody = settings.renderBody(response.capture(), response.contentType());
                // сколько прочитало приложение; остаток, который HTTP-клиент дочитывает при закрытии, не учитывается
                message.field("http.response.body.size", response.capture().total())
                    .field("http.response.body.content", responseBody)
                    .field(HttpLogFields.ERROR_CODE,
                        status != null && status >= CLIENT_ERROR ? HttpLogSettings.errorCode(responseBody) : null);
            }
        }
        return message;
    }
}
