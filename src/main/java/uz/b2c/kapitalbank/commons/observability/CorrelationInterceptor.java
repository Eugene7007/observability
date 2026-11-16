package uz.b2c.kapitalbank.commons.observability;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Передаёт идентификаторы корреляции в исходящие вызовы RestTemplate: correlationId и clientDeviceId,
 * как прежний TraceInfoInterceptor, и userGuid/deviceId, как раньше делал baggage трейсинга.
 * Вне входящего запроса (например, в фоновой задаче) генерирует новый correlationId.
 */
public class CorrelationInterceptor implements ClientHttpRequestInterceptor, Ordered {

    private final List<String> userContextHosts;

    public CorrelationInterceptor() {
        this(List.of());
    }

    /**
     * @param userContextHosts хосты, которым передаются UserGuid и DeviceId; пусто — всем
     */
    public CorrelationInterceptor(final List<String> userContextHosts) {
        this.userContextHosts = userContextHosts.stream().map(h -> h.toLowerCase(Locale.ROOT).trim()).toList();
    }

    @Override
    public int getOrder() {
        // раньше логирующего интерцептора, чтобы заголовки корреляции попали в лог исходящего вызова
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ClientHttpResponse intercept(final HttpRequest request, final byte[] body,
                                        final ClientHttpRequestExecution execution) throws IOException {
        HttpHeaders headers = request.getHeaders();
        String correlationId = MDC.get(Correlation.CORRELATION_ID);
        if (correlationId == null) {
            correlationId = Correlation.newId();
        }
        String clientDeviceId = MDC.get(Correlation.CLIENT_DEVICE_ID);
        // заголовки, которые сервис поставил сам (например, вызов от имени другого клиента), не перезаписываются
        setIfAbsent(headers, Correlation.CORRELATION_ID, correlationId);
        setIfAbsent(headers, Correlation.CLIENT_DEVICE_ID, clientDeviceId != null ? clientDeviceId : correlationId);
        if (userContextAllowed(request.getURI().getHost())) {
            setIfPresent(headers, Correlation.USER_GUID_HEADER, MDC.get(Correlation.USER_GUID));
            setIfPresent(headers, Correlation.DEVICE_ID_HEADER, MDC.get(Correlation.DEVICE_ID));
        }
        return execution.execute(request, body);
    }

    boolean userContextAllowed(final String host) {
        if (userContextHosts.isEmpty()) {
            return true;
        }
        String lower = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return userContextHosts.stream().anyMatch(pattern -> matches(pattern, lower));
    }

    private static boolean matches(final String pattern, final String host) {
        if (pattern.startsWith("*")) {
            return host.endsWith(pattern.substring(1));
        }
        if (pattern.endsWith("*")) {
            return host.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return host.equals(pattern);
    }

    private static void setIfPresent(final HttpHeaders headers, final String name, final String value) {
        if (value != null) {
            setIfAbsent(headers, name, value);
        }
    }

    private static void setIfAbsent(final HttpHeaders headers, final String name, final String value) {
        if (!headers.containsKey(name)) {
            headers.set(name, value);
        }
    }
}
