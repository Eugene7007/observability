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

        HttpLogMessage message = new HttpLogMessage(summary)
            .field("http.direction", "out")
            .field("http.request.method", request.getMethod())
            .field("url.full", target)
            .field("http.response.status_code", status)
            .field("event.duration_ms", durationMs)
            .field("http.request.headers", settings.renderHeaders(headers.keySet(), headers::getFirst))
            .field("error.type", failure == null ? null : failure.getClass().getName());

        if (bodies) {
            message.field("http.request.body.bytes", body == null ? 0 : body.length)
                .field("http.request.body.content",
                    settings.renderBody(body, headers.getFirst(HttpHeaders.CONTENT_TYPE)));
            if (response != null) {
                // сколько прочитало приложение; остаток, который HTTP-клиент дочитывает при закрытии, не учитывается
                message.field("http.response.body.bytes", response.capture().total())
                    .field("http.response.body.content", settings.renderBody(response.capture(), response.contentType()));
            }
        }
        return message;
    }
}
