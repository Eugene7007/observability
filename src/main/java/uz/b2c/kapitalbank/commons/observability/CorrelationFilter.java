package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Кладёт идентификаторы входящего запроса в MDC: correlationId и clientDeviceId (возвращаются и в заголовках
 * ответа), а также userGuid и deviceId, которые раньше попадали в логи через baggage трейсинга.
 * Если вызывающий не прислал correlationId, он генерируется, а clientDeviceId берётся равным ему —
 * так же, как делал TraceInfoFilter старой библиотеки.
 */
public class CorrelationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(final HttpServletRequest request,
                                    final HttpServletResponse response,
                                    final FilterChain chain) throws ServletException, IOException {
        String correlationId = Correlation.sanitizeOrNew(request.getHeader(Correlation.CORRELATION_ID));
        String clientDeviceId = Correlation.sanitize(request.getHeader(Correlation.CLIENT_DEVICE_ID));
        if (clientDeviceId == null) {
            clientDeviceId = correlationId;
        }
        // заголовки ставятся до обработки: после коммита ответа добавить их уже нельзя
        response.setHeader(Correlation.CORRELATION_ID, correlationId);
        response.setHeader(Correlation.CLIENT_DEVICE_ID, clientDeviceId);
        MDC.put(Correlation.CORRELATION_ID, correlationId);
        MDC.put(Correlation.CLIENT_DEVICE_ID, clientDeviceId);
        putIfPresent(Correlation.USER_GUID, request.getHeader(Correlation.USER_GUID_HEADER));
        putIfPresent(Correlation.DEVICE_ID, request.getHeader(Correlation.DEVICE_ID_HEADER));
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(Correlation.CORRELATION_ID);
            MDC.remove(Correlation.CLIENT_DEVICE_ID);
            MDC.remove(Correlation.USER_GUID);
            MDC.remove(Correlation.DEVICE_ID);
        }
    }

    private static void putIfPresent(final String key, final String headerValue) {
        String value = Correlation.sanitize(headerValue);
        if (value != null) {
            MDC.put(key, value);
        }
    }
}
