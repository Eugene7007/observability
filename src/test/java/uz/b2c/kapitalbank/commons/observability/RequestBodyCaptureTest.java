package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;

import java.io.BufferedReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тело входящего запроса: приложение получает его целиком, байт в байт, а начало тела есть в записи о любой
 * ошибке — даже если приложение тело не читало (запрос отклонён до контроллера).
 */
class RequestBodyCaptureTest {

    private static final String BODY = "{\"amount\":100,\"pan\":\"8600123456781233\"}";
    private static final String MASKED = "{\"amount\":100,\"pan\":\"860012******1233\"}";

    private final HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
    private final HttpServerLoggingFilter filter =
        new HttpServerLoggingFilter(new HttpLogSettings(new HttpLogProperties()), sink);

    @Test
    void controllerReceivesFullBody() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request(BODY), new MockHttpServletResponse(), (req, res) -> {
            seen.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            ((HttpServletResponse) res).setStatus(422);
        });

        assertThat(seen.get()).isEqualTo(BODY);
        assertThat(sink.single(Level.WARN).get("http.request.body.content")).isEqualTo(MASKED);
    }

    @Test
    void controllerReceivesLargeBodyByteForByteWhileLogKeepsOnlyTheLimit() throws Exception {
        byte[] large = new byte[200_000];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) ('a' + i % 26);
        }
        String body = "{\"text\":\"" + new String(large, StandardCharsets.US_ASCII) + "\"}";
        AtomicReference<byte[]> seen = new AtomicReference<>();

        filter.doFilter(request(body), new MockHttpServletResponse(), (req, res) -> {
            // читает кусками разного размера, как Jackson
            byte[] buffer = new byte[777];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int read;
            while ((read = req.getInputStream().read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            seen.set(out.toByteArray());
            ((HttpServletResponse) res).setStatus(400);
        });

        assertThat(Arrays.equals(seen.get(), body.getBytes(StandardCharsets.US_ASCII))).isTrue();
        HttpLogMessage message = sink.single(Level.WARN);
        assertThat(message.get("http.request.body.bytes")).isEqualTo(String.valueOf(body.length()));
        assertThat(message.get("http.request.body.content")).endsWith(LogTextMasker.TRUNCATED_MARK);
    }

    @Test
    void readerUsesRequestCharset() throws Exception {
        String text = "{\"comment\":\"Привет\"}";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay");
        request.setContentType("application/json;charset=windows-1251");
        request.setCharacterEncoding("windows-1251");
        request.setContent(text.getBytes(Charset.forName("windows-1251")));
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            BufferedReader reader = req.getReader();
            seen.set(reader.readLine());
        });

        assertThat(seen.get()).isEqualTo(text);
    }

    @Test
    void unreadBodyIsLoggedWhenRequestRejectedBeforeReading() throws Exception {
        // например, не хватает обязательного заголовка или нет такого пути: ответ до чтения тела
        for (int status : new int[] {400, 404, 405, 415, 500}) {
            HttpServerLoggingFilterTest.RecordingSink local = new HttpServerLoggingFilterTest.RecordingSink();
            new HttpServerLoggingFilter(new HttpLogSettings(new HttpLogProperties()), local)
                .doFilter(request(BODY), new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(status));

            HttpLogMessage message = local.single(status >= 500 ? Level.ERROR : Level.WARN);
            assertThat(message.get("http.request.body.content")).as("status %d", status).isEqualTo(MASKED);
            assertThat(message.get("http.request.body.bytes")).isEqualTo(String.valueOf(BODY.length()));
        }
    }

    @Test
    void chunkedBodyWithoutContentLengthIsCaptured() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.addHeader("Transfer-Encoding", "chunked");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            seen.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            ((HttpServletResponse) res).setStatus(409);
        });

        assertThat(seen.get()).isEqualTo(BODY);
        assertThat(sink.single(Level.WARN).get("http.request.body.content")).isEqualTo(MASKED);
    }

    @Test
    void successfulRequestHasNoBodyContent() throws Exception {
        filter.doFilter(request(BODY), new MockHttpServletResponse(), (req, res) -> { });

        assertThat(sink.single(Level.INFO).get("http.request.body.content")).isNull();
    }

    @Test
    void formPostIsLoggedAsMaskedParametersAndAppStillSeesThem() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/login");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent("login=ivan&password=Qwerty1&cardNumber=8600123456781233".getBytes(StandardCharsets.UTF_8));
        request.setQueryString("lang=ru");
        request.addParameter("lang", "ru");
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> raw = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            seen.set(req.getParameter("password") + "," + req.getParameter("lang") + "," + req.getParameterMap().size());
            raw.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            ((HttpServletResponse) res).setStatus(401);
        });

        assertThat(seen.get()).isEqualTo("Qwerty1,ru,4");
        // сырое тело формы доступно приложению (проверка подписи и т. п.)
        assertThat(raw.get()).isEqualTo("login=ivan&password=Qwerty1&cardNumber=8600123456781233");
        assertThat(sink.single(Level.WARN).get("http.request.body.content"))
            .isEqualTo("{\"login\":\"ivan\",\"password\":\"***\",\"cardNumber\":\"860012******1233\"}");
    }

    @Test
    void multipartIsLoggedAsPartsWithoutContent() throws Exception {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/documents");
        request.setContentType("multipart/form-data; boundary=x");
        MockPart file = new MockPart("passport", "ivanov_passport.jpg", new byte[1234]);
        file.getHeaders().setContentType(org.springframework.http.MediaType.IMAGE_JPEG);
        request.addPart(file);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            ((jakarta.servlet.http.HttpServletRequest) req).getParts();
            ((HttpServletResponse) res).setStatus(413);
        });

        String content = sink.single(Level.WARN).get("http.request.body.content");
        assertThat(content).isEqualTo("[passport image/jpeg 1234 bytes]");
        assertThat(content).doesNotContain("ivanov");
    }

    @Test
    void multipartRejectedBeforeParsingIsNotParsedByTheLibrary() throws Exception {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest() {
            @Override
            public java.util.Collection<jakarta.servlet.http.Part> getParts() {
                throw new AssertionError("библиотека не должна разбирать multipart сама");
            }
        };
        request.setMethod("POST");
        request.setRequestURI("/api/documents");
        request.setContentType("multipart/form-data; boundary=x");
        request.setContent(new byte[5000]);

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> ((HttpServletResponse) res).setStatus(401));

        assertThat(sink.single(Level.WARN).get("http.request.body.content"))
            .isEqualTo("[multipart/form-data; boundary=x, 5000 bytes, not parsed]");
    }

    private static MockHttpServletRequest request(final String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
