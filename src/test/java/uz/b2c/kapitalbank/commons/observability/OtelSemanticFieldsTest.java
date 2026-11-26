package uz.b2c.kapitalbank.commons.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import uz.b2c.kapitalbank.commons.observability.log4j.LibraryLookupAccess;

/** Поля HTTP-записей по OpenTelemetry semantic conventions и собственные поля uz.kapitalbank.*. */
class OtelSemanticFieldsTest {

    @Test
    void errorCodeIsTakenFromTopLevelOrErrorObject() {
        assertThat(HttpLogSettings.errorCode("{\"code\":\"LIMIT\",\"message\":\"m\"}")).isEqualTo("LIMIT");
        assertThat(HttpLogSettings.errorCode("{\"errorCode\":1203}")).isEqualTo("1203");
        assertThat(HttpLogSettings.errorCode("{\"error_code\":\"E1\"}")).isEqualTo("E1");
        assertThat(HttpLogSettings.errorCode("{\"data\":{\"code\":\"X\"},\"error\":{\"message\":\"m\",\"code\":\"DECLINED\"}}"))
            .isEqualTo("DECLINED");
        // скрытое маскированием, длинный текст, не JSON, вложенный не в error — не код
        assertThat(HttpLogSettings.errorCode("{\"code\":\"***\"}")).isNull();
        assertThat(HttpLogSettings.errorCode("{\"code\":\"" + "x".repeat(65) + "\"}")).isNull();
        assertThat(HttpLogSettings.errorCode("[unparseable body, 10 bytes]")).isNull();
        assertThat(HttpLogSettings.errorCode("{\"data\":{\"code\":\"X\"}}")).isNull();
        assertThat(HttpLogSettings.errorCode("{\"code\":\"A\",\"b\":...[truncated]")).isEqualTo("A");
    }

    @Test
    void serverErrorWithoutExceptionHasStatusAsErrorType() throws Exception {
        HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
        HttpServerLoggingFilter filter = new HttpServerLoggingFilter(new HttpLogSettings(new HttpLogProperties()), sink);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/x");
        request.setScheme("https");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(503);
            res.setContentType("application/json");
            res.getOutputStream().write("{\"code\":\"UNAVAILABLE\"}".getBytes(StandardCharsets.UTF_8));
        });
        HttpLogMessage message = sink.single(Level.ERROR);
        assertThat(message.get("error.type")).isEqualTo("503");
        assertThat(message.get("url.scheme")).isEqualTo("https");
        assertThat(message.get(HttpLogFields.ERROR_CODE)).isEqualTo("UNAVAILABLE");
        assertThat(message.getData()).containsKey(HttpLogFields.DURATION_MS)
            .doesNotContainKeys("http.direction", "event.duration_ms", "http.request.headers", "http.request.body.bytes");
    }

    @Test
    void clientErrorOnServerSideIsNotAnError() throws Exception {
        HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
        HttpServerLoggingFilter filter = new HttpServerLoggingFilter(new HttpLogSettings(new HttpLogProperties()), sink);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/x"), new MockHttpServletResponse(),
            (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(404));
        // OpenTelemetry: для сервера 4xx — не ошибка, error.type не ставится
        assertThat(sink.single(Level.WARN).getData()).doesNotContainKey("error.type");
    }

    @Test
    void headerWithSeveralValuesIsAnArray() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setAllowedHeaders(java.util.Set.of("Accept-Language"));
        HttpLogMessage message = new HttpLogMessage("t");
        new HttpLogSettings(properties).addRequestHeaders(message, List.of("Accept-Language"), name -> List.of("ru", "uz"));
        assertThat(message.getData()).containsEntry("http.request.header.accept-language", List.of("ru", "uz"));
    }

    @Test
    void libraryNameAndVersionAreKnown() {
        assertThat(LibraryLookupAccess.lookup("name")).isEqualTo("observability-starter");
        // без подстановки Gradle — unknown, со сборкой Gradle — версия проекта
        assertThat(LibraryLookupAccess.lookup("version")).isNotBlank().doesNotStartWith("$");
        assertThat(LibraryLookupAccess.lookup("other")).isNull();
    }
    @Test
    void errorCodeIgnoresNullAndBoolean() {
        assertThat(HttpLogSettings.errorCode("{\"code\":null,\"errorCode\":\"E2\"}")).isEqualTo("E2");
        assertThat(HttpLogSettings.errorCode("{\"code\":true}")).isNull();
        // код ищется только в начале тела
        assertThat(HttpLogSettings.errorCode("{\"items\":[" + "1,".repeat(5000) + "1],\"code\":\"LATE\"}")).isNull();
    }

    @Test
    void headerValuesAreMasked() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setAllowedHeaders(java.util.Set.of("x-card"));
        HttpLogMessage message = new HttpLogMessage("t");
        new HttpLogSettings(properties).addRequestHeaders(message, List.of("X-Card"),
            name -> List.of("8600123456781233", "+998901234567"));
        assertThat(message.getData().get("http.request.header.x-card"))
            .isEqualTo(List.of("860012******1233", "+********4567"));
    }

    @Test
    void outgoingClientErrorHttpsAndAllHeaderValues() throws Exception {
        HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
        org.springframework.mock.http.client.MockClientHttpRequest request =
            new org.springframework.mock.http.client.MockClientHttpRequest(org.springframework.http.HttpMethod.POST,
                java.net.URI.create("https://user:pwd@partner.internal/api/pay?cardId=8600123456781233&lang=ru"));
        request.getHeaders().add("Content-Type", "application/json");
        request.getHeaders().add("Accept-Language", "ru");
        request.getHeaders().add("Accept-Language", "uz");
        org.springframework.mock.http.client.MockClientHttpResponse wire =
            new org.springframework.mock.http.client.MockClientHttpResponse(
                "{\"errorCode\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8),
                org.springframework.http.HttpStatus.NOT_FOUND);
        wire.getHeaders().add("Content-Type", "application/json");
        HttpLogProperties properties = new HttpLogProperties();
        properties.getAllowedHeaders().add("accept-language");
        var response = new HttpClientLoggingInterceptor(new HttpLogSettings(properties), sink)
            .intercept(request, "{}".getBytes(StandardCharsets.UTF_8), (req, body) -> wire);
        response.getBody().readAllBytes();
        response.close();

        HttpLogMessage message = sink.single(Level.WARN);
        assertThat(message.get("error.type")).isEqualTo("404");
        assertThat(message.get("server.port")).isEqualTo("443");
        assertThat(message.get("server.address")).isEqualTo("partner.internal");
        assertThat(message.get("url.full")).isEqualTo("https://partner.internal/api/pay?cardId=REDACTED&lang=REDACTED");
        assertThat(message.getData().get("http.request.header.accept-language")).isEqualTo(List.of("ru", "uz"));
        assertThat(message.get(HttpLogFields.ERROR_CODE)).isEqualTo("NOT_FOUND");
        assertThat(message.getData().toString()).doesNotContain("pwd").doesNotContain("8600123456781233");
    }

    @Test
    void hostThatUriCannotParseIsStillKnown() {
        java.net.URI uri = java.net.URI.create("http://my_partner:8080/api/pay");
        assertThat(uri.getHost()).isNull();
        assertThat(HttpClientLoggingInterceptor.host(uri)).isEqualTo("my_partner");
        assertThat(HttpClientLoggingInterceptor.explicitPort(uri)).isEqualTo(8080);
        java.net.URI withUser = java.net.URI.create("http://u:p@my_partner/api");
        assertThat(HttpClientLoggingInterceptor.host(withUser)).isEqualTo("my_partner");
        assertThat(HttpClientLoggingInterceptor.explicitPort(withUser)).isEqualTo(-1);
    }

    @Test
    void methodQueryAndProtocolFollowOpenTelemetry() {
        assertThat(HttpLogSettings.otelMethod("GET")).isEqualTo("GET");
        assertThat(HttpLogSettings.otelMethod("PROPFIND")).isEqualTo("_OTHER");
        assertThat(HttpLogSettings.otelMethod("get")).isEqualTo("_OTHER");
        assertThat(HttpLogSettings.redactQuery("a=1&b&c=")).isEqualTo("a=REDACTED&b&c=REDACTED");
        assertThat(HttpLogSettings.redactQuery("")).isNull();
        assertThat(HttpLogSettings.protocolVersion("HTTP/1.1")).isEqualTo("1.1");
        assertThat(HttpLogSettings.protocolVersion("HTTP/2.0")).isEqualTo("2");
        assertThat(HttpLogSettings.protocolVersion("HTTP/1.0")).isEqualTo("1.0");
    }

    @Test
    void forwardedHeadersDefineSchemeHostPortAndClient() throws Exception {
        HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
        HttpServerLoggingFilter filter = new HttpServerLoggingFilter(new HttpLogSettings(new HttpLogProperties()), sink);
        MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/api/x");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "api.kapitalbank.uz");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        request.addHeader("User-Agent", "KapitalbankApp/5.1");
        request.setQueryString("phone=998901234567");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
        HttpLogMessage message = sink.single(Level.INFO);
        assertThat(message.get("url.scheme")).isEqualTo("https");
        assertThat(message.get("server.address")).isEqualTo("api.kapitalbank.uz");
        assertThat(message.get("server.port")).isEqualTo("443");
        assertThat(message.get("client.address")).isEqualTo("203.0.113.7");
        assertThat(message.get("user_agent.original")).isEqualTo("KapitalbankApp/5.1");
        assertThat(message.get("url.query")).isEqualTo("phone=REDACTED");
        assertThat(message.get("http.request.method")).isEqualTo("_OTHER");
        assertThat(message.get("http.request.method_original")).isEqualTo("PROPFIND");
        assertThat(message.getData()).doesNotContainKey("http.request.header.user-agent");
    }

    @Test
    void declaredContentLengthIsTheWireSize() throws Exception {
        HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
        HttpServerLoggingFilter filter = new HttpServerLoggingFilter(new HttpLogSettings(new HttpLogProperties()), sink);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/x");
        request.setContentType("application/json");
        request.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            req.getInputStream().readAllBytes();
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(400);
            res.setContentType("application/json");
            res.setContentLength(2);
            res.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
        });
        HttpLogMessage message = sink.single(Level.WARN);
        assertThat(message.get("http.request.body.size")).isEqualTo("7");
        assertThat(message.get("http.response.body.size")).isEqualTo("2");
        assertThat(message.get(HttpLogFields.RESPONSE_BODY_APP_SIZE)).isEqualTo("2");
    }
}
