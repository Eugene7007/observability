package uz.b2c.kapitalbank.commons.observability.e2e;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.multipart.MultipartFile;
import uz.b2c.kapitalbank.commons.observability.autoconfigure.ObservabilityAutoConfiguration;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Защитный тест на настоящем Tomcat: приложение получает ровно то, что прислал клиент, а в записи о каждой ошибке
 * есть замаскированное тело запроса. Без MockMvc и заглушек: прошлые тесты проверяли только лог и пропустили
 * обёртку, после которой контроллер получал пустое тело.
 */
class TomcatTransparencyTest {

    private static final String PAN = "8600123456781233";
    private static final String BODY = "{\"pan\":\"" + PAN + "\",\"cvv\":\"123\",\"amount\":100}";
    private static final String MASKED = "{\\\"pan\\\":\\\"860012******1233\\\",\\\"cvv\\\":\\\"***\\\",\\\"amount\\\":100}";

    private static final PrintStream ORIGINAL = System.out;
    private static final ByteArrayOutputStream LOG = new ByteArrayOutputStream();
    private static ConfigurableApplicationContext context;
    private static int port;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @RestController
    static class Endpoints {

        @PostMapping("/echo")
        String echo(@RequestBody final byte[] body) {
            return digest(body);
        }

        @PostMapping(path = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> json(@RequestBody final Map<String, Object> body) {
            return body;
        }

        @PostMapping(path = "/reader", produces = "text/plain;charset=UTF-8")
        String reader(final HttpServletRequest request) throws Exception {
            return request.getReader().lines().collect(Collectors.joining("\n"));
        }

        @PostMapping("/form")
        String form(@RequestParam final String login, @RequestParam final String password) {
            return login + "," + password;
        }

        @PostMapping("/multipart")
        String multipart(@RequestPart("file") final MultipartFile file) throws Exception {
            return digest(file.getBytes());
        }

        @PostMapping(path = "/raw-form", produces = "text/plain;charset=UTF-8")
        String rawForm(final HttpServletRequest request) throws Exception {
            // как при проверке подписи callback'а: сырое тело формы и параметры
            String raw = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return raw + "|" + request.getParameter("a") + "|" + request.getParameter("q");
        }

        @PostMapping("/reject")
        ResponseEntity<String> reject() {
            // отклоняет, не читая тело
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"bad\"}");
        }

        @PostMapping("/fail")
        String fail(@RequestBody final byte[] body) {
            throw new IllegalStateException("boom");
        }

        @PostMapping("/deferred")
        DeferredResult<ResponseEntity<String>> deferred(@RequestBody final byte[] body) {
            DeferredResult<ResponseEntity<String>> result = new DeferredResult<>();
            CompletableFuture.runAsync(() -> result.setResult(ResponseEntity.status(503)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"later\"}")));
            return result;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
        MultipartAutoConfiguration.class, ErrorMvcAutoConfiguration.class, ObservabilityAutoConfiguration.class})
    @Import(Endpoints.class)
    static class App {

        /** «Security» сервиса: отклоняет /secure/** до DispatcherServlet, не читая тело. */
        @Bean
        FilterRegistrationBean<jakarta.servlet.Filter> security() {
            FilterRegistrationBean<jakarta.servlet.Filter> registration = new FilterRegistrationBean<>((req, res, chain) -> {
                if (((HttpServletRequest) req).getRequestURI().startsWith("/secure/")) {
                    ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401);
                    return;
                }
                chain.doFilter(req, res);
            });
            registration.setOrder(0);
            return registration;
        }

        /** Сервлет с неблокирующим чтением тела (Servlet async IO). */
        @Bean
        ServletRegistrationBean<jakarta.servlet.http.HttpServlet> nonBlocking() {
            ServletRegistrationBean<jakarta.servlet.http.HttpServlet> registration = new ServletRegistrationBean<>(
                new jakarta.servlet.http.HttpServlet() {
                    @Override
                    protected void doPost(final HttpServletRequest req, final jakarta.servlet.http.HttpServletResponse res)
                        throws java.io.IOException {
                        jakarta.servlet.AsyncContext async = req.startAsync();
                        jakarta.servlet.ServletInputStream in = req.getInputStream();
                        ByteArrayOutputStream body = new ByteArrayOutputStream();
                        in.setReadListener(new jakarta.servlet.ReadListener() {
                            @Override
                            public void onDataAvailable() throws java.io.IOException {
                                byte[] buffer = new byte[1024];
                                while (in.isReady() && !in.isFinished()) {
                                    int read = in.read(buffer);
                                    if (read > 0) {
                                        body.write(buffer, 0, read);
                                    }
                                }
                            }

                            @Override
                            public void onAllDataRead() throws java.io.IOException {
                                res.getWriter().write(digest(body.toByteArray()));
                                async.complete();
                            }

                            @Override
                            public void onError(final Throwable t) {
                                async.complete();
                            }
                        });
                    }
                }, "/nb");
            registration.setAsyncSupported(true);
            return registration;
        }
    }

    @BeforeAll
    static void start() {
        System.setOut(new PrintStream(LOG, true, StandardCharsets.UTF_8));
        context = new SpringApplicationBuilder(App.class)
            .web(WebApplicationType.SERVLET)
            .properties("spring.application.name=tomcat-e2e", "server.port=0")
            .run();
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        context.close();
        LogManager.shutdown();
        System.setOut(ORIGINAL);
    }

    // ---------- приложение получает то же, что прислал клиент ----------

    @Test
    void jsonBodyArrivesByteForByte() throws Exception {
        assertThat(post("/echo", "application/json", BODY.getBytes(StandardCharsets.UTF_8)).body())
            .isEqualTo(digest(BODY.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void oneMegabyteBodyArrivesByteForByte() throws Exception {
        byte[] large = new byte[1024 * 1024];
        new java.util.Random(7).nextBytes(large);
        assertThat(post("/echo", "application/octet-stream", large).body()).isEqualTo(digest(large));
    }

    @Test
    void chunkedBodyArrivesByteForByte() throws Exception {
        byte[] body = BODY.repeat(500).getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(uri("/echo")).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))).build();
        assertThat(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body()).isEqualTo(digest(body));
    }

    @Test
    void readerUsesRequestCharset() throws Exception {
        String text = "Привет, мир";
        HttpResponse<String> response = post("/reader", "text/plain;charset=windows-1251",
            text.getBytes(Charset.forName("windows-1251")));
        assertThat(response.body()).isEqualTo(text);
    }

    @Test
    void formParametersArrive() throws Exception {
        HttpResponse<String> response = post("/form", "application/x-www-form-urlencoded",
            "login=ivan&password=Qwerty1".getBytes(StandardCharsets.UTF_8));
        assertThat(response.body()).isEqualTo("ivan,Qwerty1");
    }

    @Test
    void multipartArrives() throws Exception {
        byte[] file = new byte[50_000];
        new java.util.Random(3).nextBytes(file);
        String boundary = "b0undary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ivanov.jpg\"\r\n"
            + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpResponse<String> response = post("/multipart", "multipart/form-data; boundary=" + boundary,
            body.toByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(digest(file));
    }

    @Test
    void rawFormBodyAndParametersBothArrive() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/raw-form?q=1"))
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString("a=%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82&sig=x")).build();
        assertThat(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body())
            .isEqualTo("a=%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82&sig=x|привет|1");
    }

    @Test
    void nonBlockingReadGetsTheWholeBody() throws Exception {
        for (byte[] body : new byte[][] {"payload".getBytes(StandardCharsets.UTF_8), new byte[20_000]}) {
            assertThat(post("/nb", "application/octet-stream", body).body()).isEqualTo(digest(body));
        }
    }

    // ---------- у каждой ошибки есть тело в логе ----------

    @Test
    void everyErrorHasMaskedRequestBodyInLog() throws Exception {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        record Case(String method, String path, String contentType, int status) {
        }
        List<Case> cases = List.of(
            new Case("POST", "/reject", "application/json", 400),
            new Case("POST", "/nope", "application/json", 404),
            new Case("PUT", "/echo", "application/json", 405),
            new Case("POST", "/json", "text/csv", 415),
            new Case("POST", "/fail", "application/json", 500),
            new Case("POST", "/deferred", "application/json", 503));
        for (Case c : cases) {
            String marker = "case-" + c.status();
            HttpRequest request = HttpRequest.newBuilder(uri(c.path() + "?" + marker))
                .header("Content-Type", c.contentType())
                .method(c.method(), HttpRequest.BodyPublishers.ofByteArray(body)).build();
            assertThat(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .as(c.toString()).isEqualTo(c.status());
        }
        TimeUnit.MILLISECONDS.sleep(300);
        String log = LOG.toString(StandardCharsets.UTF_8);
        for (Case c : cases) {
            String line = log.lines().filter(l -> l.contains("\"http.direction\":\"in\"")
                    && l.contains(c.method() + " " + c.path() + " ->"))
                .reduce((a, b) -> b).orElse("");
            if (c.contentType().contains("json")) {
                assertThat(line).as("log line for %s", c).contains("\"http.request.body.content\":\"" + MASKED + "\"");
            } else {
                // тело объявлено текстом: маскер строк лога
                assertThat(line).as("log line for %s", c).contains("\"http.request.body.content\":")
                    .contains("860012******1233").contains("cvv\\\":\\\"***");
            }
            assertThat(line).contains("\"http.request.body.bytes\":" + body.length);
            // тело ответа: своё у приложения, страница ошибки Spring Boot — у sendError и исключений
            assertThat(line).as("response body for %s", c).contains("\"http.response.body.content\":");
            // MDC запроса есть и в записи, отложенной до страницы ошибки
            assertThat(line).contains("\"correlation_id\":");
        }
        assertThat(log).doesNotContain(PAN);
        // страница ошибки не даёт отдельной записи
        assertThat(log).doesNotContain("HTTP in POST /error").doesNotContain("HTTP in PUT /error");
    }

    @Test
    void multipartRejectedBeforeControllerIsNotParsedByTheLibrary() throws Exception {
        String boundary = "b0undary";
        byte[] file = new byte[900_000];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ivanov.jpg\"\r\n"
            + "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        assertThat(post("/secure/upload", "multipart/form-data; boundary=" + boundary, body.toByteArray()).statusCode())
            .isEqualTo(401);
        TimeUnit.MILLISECONDS.sleep(300);
        String line = LOG.toString(StandardCharsets.UTF_8).lines()
            .filter(l -> l.contains("POST /secure/upload -> 401")).reduce((x, y) -> y).orElse("");
        assertThat(line).contains("not parsed").doesNotContain("ivanov");
    }

    @Test
    void formErrorLogsMaskedParameters() throws Exception {
        HttpRequest missing = HttpRequest.newBuilder(uri("/form"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("password=Qwerty1&cardNumber=" + PAN)).build();
        assertThat(CLIENT.send(missing, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        TimeUnit.MILLISECONDS.sleep(300);
        String line = LOG.toString(StandardCharsets.UTF_8).lines()
            .filter(l -> l.contains("POST /form -> 400")).reduce((a, b) -> b).orElse("");
        assertThat(line).contains("\\\"password\\\":\\\"***\\\"").contains("860012******1233")
            .doesNotContain("Qwerty1").doesNotContain(PAN);
    }

    // ---------- тело, пришедшее отдельным пакетом после заголовков ----------

    @Test
    void bodyArrivingAfterHeadersIsLoggedForRejectedRequest() throws Exception {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /late-body HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Expect: 100-continue\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            TimeUnit.MILLISECONDS.sleep(300);
            out.write(body);
            out.flush();
            InputStream in = socket.getInputStream();
            String status = readLine(in);
            if (status.startsWith("HTTP/1.1 100")) {
                readLine(in);
                status = readLine(in);
            }
            assertThat(status).startsWith("HTTP/1.1 404");
        }
        TimeUnit.MILLISECONDS.sleep(300);
        String line = LOG.toString(StandardCharsets.UTF_8).lines()
            .filter(l -> l.contains("POST /late-body -> 404")).reduce((x, y) -> y).orElse("");
        assertThat(line).contains("\"http.request.body.content\":\"" + MASKED + "\"");
    }

    // ---------- helpers ----------

    private static HttpResponse<String> post(final String path, final String contentType, final byte[] body)
        throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static URI uri(final String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    static String digest(final byte[] data) {
        try {
            return data.length + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readLine(final InputStream in) throws Exception {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (c != '\r') {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    private static final class TeeStream extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        TeeStream(final OutputStream a, final OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public synchronized void write(final int c) throws java.io.IOException {
            a.write(c);
            b.write(c);
        }

        @Override
        public synchronized void write(final byte[] data, final int off, final int len) throws java.io.IOException {
            a.write(data, off, len);
            b.write(data, off, len);
        }
    }
}
