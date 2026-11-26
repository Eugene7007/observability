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
            long contentLength = response.getHeaders().getContentLength();
            result = new CapturingClientHttpResponse(response, capture,
                () -> log(request, body, true, status, new CapturedResponse(capture, contentType, contentLength), null, start));
        } else {
            log(request, body, false, status, null, null, start);
        }
        return result;
    }

    /** Хост без учётных данных: из URI, а если URI его не разобрал (подчёркивание в имени) — из authority. */
    static String host(final URI uri) {
        if (uri.getHost() != null) {
            return uri.getHost();
        }
        String authority = uri.getRawAuthority();
        if (authority == null) {
            return null;
        }
        int at = authority.lastIndexOf('@');
        String hostPort = at < 0 ? authority : authority.substring(at + 1);
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            return end > 0 ? hostPort.substring(0, end + 1) : hostPort;
        }
        int colon = hostPort.lastIndexOf(':');
        return colon < 0 ? hostPort : hostPort.substring(0, colon);
    }

    /** Порт из URL или -1, в том числе когда URI не разобрал authority. */
    static int explicitPort(final URI uri) {
        if (uri.getHost() != null || uri.getRawAuthority() == null) {
            return uri.getPort();
        }
        String authority = uri.getRawAuthority();
        String hostPort = authority.substring(authority.lastIndexOf('@') + 1);
        int colon = hostPort.lastIndexOf(':');
        if (colon < 0 || hostPort.lastIndexOf(']') > colon) {
            return -1;
        }
        try {
            return Integer.parseInt(hostPort.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Порт по умолчанию для схемы, как требует OpenTelemetry для server.port. */
    private static Integer defaultPort(final URI uri) {
        String scheme = uri.getScheme();
        return "https".equalsIgnoreCase(scheme) ? Integer.valueOf(HTTPS_PORT)
            : "http".equalsIgnoreCase(scheme) ? Integer.valueOf(HTTP_PORT) : null;
    }

    private record CapturedResponse(BodyCapture capture, String contentType, long declaredLength) {
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
        // без учётных данных из user@host; хост берётся и тогда, когда URI его не разобрал (my_partner:8080)
        String host = host(uri);
        int explicitPort = explicitPort(uri);
        String origin = uri.getScheme() + "://" + host + (explicitPort < 0 ? "" : ":" + explicitPort);
        // номер карты в пути маскируется; в message и прежнем формате — без query
        String target = origin + LogTextMasker.maskPath(uri.getRawPath());
        String query = HttpLogSettings.redactQuery(uri.getRawQuery());
        String outcome = failure != null ? "FAILED (" + failure.getClass().getSimpleName() + ")" : String.valueOf(status);
        String summary = "HTTP out " + request.getMethod() + " " + target + " -> " + outcome + " in " + durationMs + " ms";
        HttpHeaders headers = request.getHeaders();
        String method = request.getMethod().name();

        // имена полей — OpenTelemetry semantic conventions (HTTP client); свои поля — под префиксом uz.kapitalbank.
        HttpLogMessage message = new HttpLogMessage(summary)
            .field("http.request.method", HttpLogSettings.otelMethod(method))
            .field("http.request.method_original",
                HttpLogSettings.knownMethod(method) ? null : LogTextMasker.maskOrPlaceholder(method))
            // url.full по OpenTelemetry: query с ключами, значения скрыты (REDACTED)
            .field("url.full", query == null ? target : target + "?" + query)
            // IPv6 без скобок, как у входящих; хост проходит маскер текста лога
            .field("server.address", host == null ? null : LogTextMasker.maskOrPlaceholder(Forwarded.stripPort(host)))
            .field("server.port", explicitPort >= 0 ? Integer.valueOf(explicitPort) : defaultPort(uri))
            .field("http.response.status_code", status)
            .field(HttpLogFields.DURATION_MS, durationMs)
            // error.type по OpenTelemetry: класс исключения, а без него у 4xx и 5xx — код статуса строкой
            .field("error.type", failure != null ? HttpServerLoggingFilter.errorType(failure)
                : status != null && status >= CLIENT_ERROR ? String.valueOf(status) : null);
        settings.addRequestHeaders(message, headers.keySet(), headers::get);
        // прежний формат для Kafka-топика (rc1/rc2), порядок полей — как раньше
        message.legacy("http.direction", "out")
            .legacy("http.request.method", request.getMethod())
            .legacy("url.full", target)
            .legacy("http.response.status_code", status)
            .legacy("event.duration_ms", durationMs)
            .legacy("http.request.headers", settings.renderHeaders(headers.keySet(), headers::getFirst))
            .legacy("error.type", failure == null ? null : failure.getClass().getName());

        if (bodies) {
            int bodyLength = body == null ? 0 : body.length;
            String requestBody = settings.renderBody(body, headers.getFirst(HttpHeaders.CONTENT_TYPE));
            // тело исходящего запроса уходит как есть: его длина — это и есть байты по сети
            message.field("http.request.body.size", headers.getContentLength() >= 0 ? headers.getContentLength() : bodyLength)
                .field("http.request.body.content", requestBody);
            message.legacy("http.request.body.bytes", bodyLength)
                .legacy("http.request.body.content", requestBody);
            if (response != null) {
                String responseBody = settings.renderBody(response.capture(), response.contentType());
                long read = response.capture().total();
                // по сети — Content-Length ответа; сколько прочитало приложение (после распаковки) — своё поле
                message.field("http.response.body.size", response.declaredLength() >= 0 ? response.declaredLength() : null)
                    .field("http.response.body.content", responseBody)
                    .field(HttpLogFields.RESPONSE_BODY_APP_SIZE, read)
                    .field(HttpLogFields.ERROR_CODE,
                        status != null && status >= CLIENT_ERROR ? HttpLogSettings.errorCode(responseBody) : null);
                message.legacy("http.response.body.bytes", read)
                    .legacy("http.response.body.content", responseBody);
            }
        }
        return message;
    }
}
