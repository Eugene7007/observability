package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpServerLoggingFilterTest {

    private final RecordingSink sink = new RecordingSink();

    private HttpServerLoggingFilter filter(final List<String> bodyPaths) {
        HttpLogProperties properties = new HttpLogProperties();
        // прежние тесты проверяют запись тел при любом статусе; режим ON_ERROR проверяется отдельно
        properties.setBodies(HttpLogProperties.Bodies.ALWAYS);
        properties.setBodyPaths(bodyPaths);
        properties.setMaxBodyBytes(256);
        return new HttpServerLoggingFilter(new HttpLogSettings(properties), sink);
    }

    private static MockHttpServletRequest post(final String path, final byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body);
        request.addHeader("Authorization", "Basic c2VjcmV0");
        request.addHeader("authorization", "Bearer token");
        request.addHeader("UserPinfl", "31234567890123");
        request.addHeader("correlationId", "c-1");
        return request;
    }

    @Test
    void logsMaskedBodiesAndOnlyAllowedHeaders() throws Exception {
        byte[] body = "{\"pan\":\"8600510343218413\",\"expiry\":\"2812\"}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = post("/internal/v1/uzcard/cards/registration", body);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            byte[] read = req.getInputStream().readAllBytes();
            assertThat(read).isEqualTo(body);
            res.setContentType("application/json");
            res.getOutputStream().write("{\"id\":7,\"maskedPhone\":\"+99890***4567\"}".getBytes(StandardCharsets.UTF_8));
        };
        filter(List.of("/internal/**")).doFilter(request, response, chain);

        assertThat(response.getContentAsString()).isEqualTo("{\"id\":7,\"maskedPhone\":\"+99890***4567\"}");
        HttpLogMessage message = sink.single(Level.INFO);
        assertThat(message.getFormattedMessage())
            .startsWith("HTTP in POST /internal/v1/uzcard/cards/registration -> 200 in ");
        assertThat(message.get("http.request.body.content")).isEqualTo("{\"pan\":\"860051******8413\",\"expiry\":\"***\"}");
        assertThat(message.get("http.response.body.content")).contains("\"id\":7");
        // заголовки по OpenTelemetry: поле на заголовок, значение — массив
        assertThat(message.get("http.request.header.correlationid")).isEqualTo("[c-1]");
        assertThat(message.getData().keySet()).noneMatch(key -> key.contains("authorization"));
        assertThat(message.getData().toString()).doesNotContain("31234567890123");
    }

    @Test
    void largeBodiesPassThroughCompletelyButOnlyHeadIsCaptured() throws Exception {
        byte[] body = new byte[2 * 1024 * 1024];
        Arrays.fill(body, (byte) 'a');
        byte[] responseBody = new byte[3 * 1024 * 1024];
        Arrays.fill(responseBody, (byte) 'b');
        MockHttpServletRequest request = post("/internal/v1/big", body);
        request.setContentType("application/octet-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> {
            assertThat(req.getInputStream().readAllBytes()).hasSize(body.length);
            res.setContentType("application/octet-stream");
            res.getOutputStream().write(responseBody);
        };
        filter(List.of("/**")).doFilter(request, response, chain);

        assertThat(response.getContentAsByteArray()).hasSize(responseBody.length);
        HttpLogMessage message = sink.single(Level.INFO);
        assertThat(message.get("http.request.body.size")).isEqualTo(String.valueOf(body.length));
        assertThat(message.get("http.request.body.content")).isEqualTo("[application/octet-stream, 2097152 bytes]");
        // Content-Length ответа не объявлен: по сети размер неизвестен, записанное приложением — своё поле
        assertThat(message.getData()).doesNotContainKey("http.response.body.size");
        assertThat(message.get(HttpLogFields.RESPONSE_BODY_APP_SIZE)).isEqualTo(String.valueOf(responseBody.length));
    }

    @Test
    void writerOutputIsFlushedToClient() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            res.setCharacterEncoding("UTF-8");
            res.setContentType("application/json");
            res.getWriter().write("{\"message\":\"Карта заблокирована\"}");
        };

        filter(List.of("/**")).doFilter(new MockHttpServletRequest("GET", "/internal/v1/x"), response, chain);

        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"message\":\"Карта заблокирована\"}");
        assertThat(sink.single(Level.INFO).get("http.response.body.content")).isEqualTo("{\"message\":\"Карта заблокирована\"}");
    }

    @Test
    void successOutsideBodyPathsHasNoBodies() throws Exception {
        MockHttpServletRequest request = post("/internal/v1/uzcard/cards/registration", "{\"pan\":\"1\"}".getBytes());
        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(201);

        filter(List.of()).doFilter(request, new MockHttpServletResponse(), chain);

        HttpLogMessage message = sink.single(Level.INFO);
        assertThat(message.get("http.response.status_code")).isEqualTo("201");
        assertThat(message.containsKey("http.request.body.content")).isFalse();
    }

    @Test
    void failureIsLoggedAsErrorAndRethrown() {
        FilterChain chain = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/x");
        assertThatThrownBy(() -> filter(List.of("/**")).doFilter(request, new MockHttpServletResponse(), chain))
            .isInstanceOf(IllegalStateException.class);
        // запись ждёт страницу ошибки; без прохода ERROR её пишет слушатель завершения запроса
        assertThat(sink.events).isEmpty();
        HttpServerLoggingFilter.completeRequest(request);

        HttpLogMessage message = sink.single(Level.ERROR);
        assertThat(message.get("http.response.status_code")).isEqualTo("500");
        assertThat(message.get("error.type")).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void panInPathIsMaskedAndRouteIsLogged() throws Exception {
        MockHttpServletRequest request =
            new MockHttpServletRequest("GET", "/internal/v1/uzcard/cards/recipient-info/8600510343218413");
        FilterChain chain = (req, res) -> req.setAttribute(
            org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
            "/internal/v1/uzcard/cards/recipient-info/{processingKey}");

        filter(List.of("/**")).doFilter(request, new MockHttpServletResponse(), chain);

        HttpLogMessage message = sink.single(Level.INFO);
        assertThat(message.getFormattedMessage()).contains("recipient-info/860051******8413").doesNotContain("1034321");
        assertThat(message.get("url.path")).isEqualTo("/internal/v1/uzcard/cards/recipient-info/860051******8413");
        assertThat(message.get("http.route")).isEqualTo("/internal/v1/uzcard/cards/recipient-info/{processingKey}");
    }

    @Test
    void loggingFailureDoesNotBreakResponse() throws Exception {
        HttpLogProperties properties = new HttpLogProperties();
        // прежние тесты проверяют запись тел при любом статусе; режим ON_ERROR проверяется отдельно
        properties.setBodies(HttpLogProperties.Bodies.ALWAYS);
        properties.setBodyPaths(List.of("/**"));
        HttpLogSink failing = new HttpLogSink() {
            @Override
            public boolean isEnabled(final Level level) {
                return true;
            }

            @Override
            public void log(final Level level, final HttpLogMessage message) {
                throw new IllegalStateException("appender is broken");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        new HttpServerLoggingFilter(new HttpLogSettings(properties), failing).doFilter(
            new MockHttpServletRequest("GET", "/internal/v1/x"), response,
            (req, res) -> res.getOutputStream().write("ok".getBytes(StandardCharsets.UTF_8)));

        assertThat(response.getContentAsString()).isEqualTo("ok");
    }

    @Test
    void actuatorIsExcluded() throws Exception {
        filter(List.of("/**")).doFilter(new MockHttpServletRequest("GET", "/actuator/prometheus"),
            new MockHttpServletResponse(), (req, res) -> { });

        assertThat(sink.events).isEmpty();
    }

    @Test
    void warnLevelStillLogsErrorsWithBodiesButSkipsSuccess() throws Exception {
        HttpLogProperties properties = new HttpLogProperties();
        // прежние тесты проверяют запись тел при любом статусе; режим ON_ERROR проверяется отдельно
        properties.setBodies(HttpLogProperties.Bodies.ALWAYS);
        properties.setBodyPaths(List.of("/**"));
        RecordingSink warnOnly = new RecordingSink(Level.WARN);
        HttpServerLoggingFilter warnFilter = new HttpServerLoggingFilter(new HttpLogSettings(properties), warnOnly);
        MockHttpServletRequest ok = new MockHttpServletRequest("GET", "/internal/v1/ok");

        warnFilter.doFilter(ok, new MockHttpServletResponse(), (req, res) -> { });
        warnFilter.doFilter(new MockHttpServletRequest("GET", "/internal/v1/down"), new MockHttpServletResponse(),
            (req, res) -> {
                ((HttpServletResponse) res).setStatus(503);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"down\"}");
            });

        HttpLogMessage message = warnOnly.single(Level.ERROR);
        assertThat(message.get("http.response.status_code")).isEqualTo("503");
        assertThat(message.get("http.response.body.content")).isEqualTo("{\"error\":\"down\"}");
    }

    static final class RecordingSink implements HttpLogSink {

        final List<Object[]> events = new ArrayList<>();
        private final Level threshold;

        RecordingSink() {
            this(Level.INFO);
        }

        RecordingSink(final Level threshold) {
            this.threshold = threshold;
        }

        @Override
        public boolean isEnabled(final Level level) {
            return level.isMoreSpecificThan(threshold);
        }

        @Override
        public void log(final Level level, final HttpLogMessage message) {
            events.add(new Object[] {level, message});
        }

        HttpLogMessage single(final Level level) {
            assertThat(events).hasSize(1);
            assertThat(events.get(0)[0]).isEqualTo(level);
            return (HttpLogMessage) events.get(0)[1];
        }
    }
}
