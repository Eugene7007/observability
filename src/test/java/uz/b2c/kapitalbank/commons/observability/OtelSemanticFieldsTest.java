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
}
