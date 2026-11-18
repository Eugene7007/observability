package uz.b2c.kapitalbank.commons.observability;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тело входящего запроса: приложение получает его целиком, а в лог оно попадает и тогда, когда приложение
 * его не читало (запрос отклонён до чтения тела).
 */
class RequestBodyCaptureTest {

    private static final String BODY = "{\"amount\":100,\"pan\":\"8600123456781233\"}";

    @Test
    void controllerReceivesFullBody() throws Exception {
        HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
        HttpServerLoggingFilter filter = new HttpServerLoggingFilter(new HttpLogSettings(new HttpLogProperties()), sink);
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> {
            seen.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(422);
        });

        assertThat(seen.get()).isEqualTo(BODY);
        assertThat(sink.single(Level.INFO).get("http.request.body.content"))
            .isEqualTo("{\"amount\":100,\"pan\":\"860012******1233\"}");
    }

    @Test
    void unreadBodyIsLoggedWhenRequestRejectedBeforeReading() throws Exception {
        HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
        HttpServerLoggingFilter filter = new HttpServerLoggingFilter(new HttpLogSettings(new HttpLogProperties()), sink);

        // например, не хватает обязательного заголовка: 400 до чтения тела
        filter.doFilter(request(), new MockHttpServletResponse(),
            (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(400));

        assertThat(sink.single(Level.INFO).get("http.request.body.content"))
            .isEqualTo("{\"amount\":100,\"pan\":\"860012******1233\"}");
    }

    @Test
    void unreadBodyIsNotReadForSuccessfulRequests() throws Exception {
        HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
        HttpServerLoggingFilter filter = new HttpServerLoggingFilter(new HttpLogSettings(new HttpLogProperties()), sink);

        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(sink.single(Level.INFO).get("http.request.body.content")).isNull();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay");
        request.setContentType("application/json");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
