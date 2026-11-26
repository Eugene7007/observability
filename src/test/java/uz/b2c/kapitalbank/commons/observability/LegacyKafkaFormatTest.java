package uz.b2c.kapitalbank.commons.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.layout.template.json.JsonTemplateLayout;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.message.StringMapMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Формат прежнего Kafka-топика ({@code layout-legacy-kafka.json}) не меняется до 2.0: его читают старые потребители.
 * Записи всех видов рендерятся этим шаблоном и сравниваются с эталоном {@code log-format-legacy-kafka.json},
 * снятым с 1.0.0-rc2 — последней версии, где формат топика проверяли. Сравниваются записи целиком: имена, типы,
 * значения и порядок полей; меняются только время и длительность. Стек исключений задаётся явно, поэтому
 * сравнивается целиком, вместе с точкой обрезки.
 * <p>
 * Эталон перезаписывается системным свойством {@code legacy.kafka.golden.write=<путь>} — только для снятия его
 * с версии, формат которой принят за прежний.
 */
class LegacyKafkaFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void kafkaTopicFormatIsUnchanged() throws Exception {
        JsonTemplateLayout layout = JsonTemplateLayout.newBuilder()
            .setConfiguration(new DefaultConfiguration())
            .setEventTemplateUri("classpath:lib-observability/layout-legacy-kafka.json")
            .build();
        Map<String, JsonNode> actual = new LinkedHashMap<>();
        for (Map.Entry<String, LogEvent> record : records().entrySet()) {
            actual.put(record.getKey(), normalize(MAPPER.readTree(layout.toSerializable(record.getValue()))));
        }
        String write = System.getProperty("legacy.kafka.golden.write");
        if (write != null && !write.isBlank()) {
            Files.writeString(Path.of(write),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n", StandardCharsets.UTF_8);
            return;
        }
        JsonNode golden;
        try (InputStream in = getClass().getResourceAsStream("/log-format-legacy-kafka.json")) {
            golden = MAPPER.readTree(in);
        }
        for (Map.Entry<String, JsonNode> record : actual.entrySet()) {
            JsonNode expected = golden.get(record.getKey());
            assertThat(expected).as("эталон записи %s", record.getKey()).isNotNull();
            assertThat(fieldOrder(record.getValue())).as("поля записи %s в Kafka", record.getKey())
                .isEqualTo(fieldOrder(expected));
            assertThat(record.getValue()).as("запись %s в Kafka", record.getKey()).isEqualTo(expected);
        }
        assertThat(actual.keySet()).containsExactlyElementsOf(iterable(golden.fieldNames()));
    }

    private static List<String> iterable(final java.util.Iterator<String> names) {
        List<String> result = new ArrayList<>();
        names.forEachRemaining(result::add);
        return result;
    }

    private static List<String> fieldOrder(final JsonNode record) {
        return iterable(record.fieldNames());
    }

    /** Время, длительность и стек меняются от запуска к запуску; остальное сравнивается как есть. */
    private static JsonNode normalize(final JsonNode record) {
        ObjectNode node = (ObjectNode) record;
        node.put("@timestamp", "T");
        if (node.has("event.duration_ms")) {
            node.put("event.duration_ms", 0);
        }
        if (node.has("message")) {
            node.put("message", node.get("message").asText().replaceAll("in \\d+ ms", "in N ms"));
        }
        return node;
    }

    private static Map<String, LogEvent> records() throws Exception {
        Map<String, LogEvent> records = new LinkedHashMap<>();
        records.put("log", event("contract-app", Level.INFO, new SimpleMessage("contract-log"), null));
        records.put("error", event("contract-app", Level.ERROR, new SimpleMessage("contract-error"),
            withStack(new IllegalStateException("card 8600123456781233"))));
        records.put("map", event("contract-map", Level.INFO,
            new StringMapMessage().with("orderId", "42").with("phone", "+998901234567"), null));

        HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

        HttpServerLoggingFilterTest.RecordingSink in = new HttpServerLoggingFilterTest.RecordingSink();
        MockHttpServletRequest request = request("/api/v1/contract-in/7");
        new HttpServerLoggingFilter(settings, in).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            req.getInputStream().readAllBytes();
            HttpServletResponse response = (HttpServletResponse) res;
            response.setStatus(422);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"LIMIT\"}");
        });
        records.put("http.in", httpEvent("observability.http.in", in));

        HttpServerLoggingFilterTest.RecordingSink failed = new HttpServerLoggingFilterTest.RecordingSink();
        MockHttpServletRequest failing = request("/api/v1/contract-failed/7");
        try {
            new HttpServerLoggingFilter(settings, failed).doFilter(failing, new MockHttpServletResponse(),
                (req, res) -> {
                    req.getInputStream().readAllBytes();
                    throw new IllegalStateException("boom");
                });
        } catch (IllegalStateException expected) {
            HttpServerLoggingFilter.completeRequest(failing);
        }
        records.put("http.in.failed", httpEvent("observability.http.in", failed));

        HttpServerLoggingFilterTest.RecordingSink out = new HttpServerLoggingFilterTest.RecordingSink();
        MockClientHttpRequest outgoing = new MockClientHttpRequest(HttpMethod.POST,
            URI.create("https://contract-out.internal/api/v1/pay?lang=ru"));
        outgoing.getHeaders().add("Content-Type", "application/json");
        MockClientHttpResponse wire = new MockClientHttpResponse(
            "{\"code\":\"DECLINED\"}".getBytes(StandardCharsets.UTF_8), HttpStatus.BAD_GATEWAY);
        wire.getHeaders().add("Content-Type", "application/json");
        ClientHttpResponse response = new HttpClientLoggingInterceptor(settings, out)
            .intercept(outgoing, "{\"amount\":100}".getBytes(StandardCharsets.UTF_8), (req, body) -> wire);
        try (InputStream body = response.getBody()) {
            body.readAllBytes();
        }
        response.close();
        records.put("http.out", httpEvent("observability.http.out", out));

        HttpServerLoggingFilterTest.RecordingSink refused = new HttpServerLoggingFilterTest.RecordingSink();
        MockClientHttpRequest refusedRequest = new MockClientHttpRequest(HttpMethod.POST,
            URI.create("http://contract-refused.internal/api/v1/pay"));
        refusedRequest.getHeaders().add("Content-Type", "application/json");
        try {
            new HttpClientLoggingInterceptor(settings, refused).intercept(refusedRequest,
                "{\"amount\":100}".getBytes(StandardCharsets.UTF_8), (req, body) -> {
                    throw new ConnectException("refused");
                });
        } catch (IOException expected) {
            // ожидаемо
        }
        records.put("http.out.failed", httpEvent("observability.http.out", refused));

        // исходящий 4xx по https с нестандартным методом
        HttpServerLoggingFilterTest.RecordingSink clientError = new HttpServerLoggingFilterTest.RecordingSink();
        MockClientHttpRequest lookup = new MockClientHttpRequest(HttpMethod.valueOf("PROPFIND"),
            URI.create("https://contract-out.internal:8443/api/v1/cards/8600123456781233?x=1"));
        lookup.getHeaders().add("x-request-id", "r 8600123456781233");
        MockClientHttpResponse notFound = new MockClientHttpResponse(
            "{\"code\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8), HttpStatus.NOT_FOUND);
        notFound.getHeaders().add("Content-Type", "application/json");
        ClientHttpResponse notFoundResponse = new HttpClientLoggingInterceptor(settings, clientError)
            .intercept(lookup, new byte[0], (req, body) -> notFound);
        try (InputStream body = notFoundResponse.getBody()) {
            body.readAllBytes();
        }
        notFoundResponse.close();
        records.put("http.out.client_error", httpEvent("observability.http.out", clientError));

        // входящий с нестандартным методом, пустым телом и номером карты в разрешённом заголовке
        HttpServerLoggingFilterTest.RecordingSink other = new HttpServerLoggingFilterTest.RecordingSink();
        MockHttpServletRequest propfind = new MockHttpServletRequest("PROPFIND", "/api/v1/contract-other/8600123456781233");
        propfind.addHeader("x-request-id", "r 8600123456781233");
        new HttpServerLoggingFilter(settings, other).doFilter(propfind, new MockHttpServletResponse(),
            (req, res) -> ((HttpServletResponse) res).setStatus(409));
        records.put("http.in.other", httpEvent("observability.http.in", other));
        return records;
    }

    /** Стек задан явно: одинаков при любом способе запуска тестов и проверяет точку обрезки на сервлете. */
    private static Throwable withStack(final Throwable error) {
        error.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("uz.example.PaymentService", "pay", "PaymentService.java", 42),
            new StackTraceElement("uz.example.PaymentController", "pay", "PaymentController.java", 17),
            new StackTraceElement("jakarta.servlet.http.HttpServlet", "service", "HttpServlet.java", 658),
            new StackTraceElement("org.apache.catalina.core.ApplicationFilterChain", "doFilter",
                "ApplicationFilterChain.java", 205)
        });
        return error;
    }

    private static MockHttpServletRequest request(final String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setQueryString("lang=ru");
        request.setContentType("application/json");
        request.setContent("{\"amount\":100,\"pan\":\"8600123456781233\"}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Accept-Language", "ru-RU");
        request.addHeader("correlationId", "c-1");
        request.addHeader("User-Agent", "KapitalbankApp/5.1 (Android 14)");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, path.replace("/7", "/{id}"));
        return request;
    }

    private static LogEvent httpEvent(final String logger, final HttpServerLoggingFilterTest.RecordingSink sink) {
        Object[] recorded = sink.events.get(sink.events.size() - 1);
        return event(logger, (Level) recorded[0], (Message) recorded[1], null);
    }

    private static LogEvent event(final String logger, final Level level, final Message message, final Throwable thrown) {
        SortedArrayStringMap mdc = new SortedArrayStringMap();
        mdc.putValue("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        mdc.putValue("spanId", "00f067aa0ba902b7");
        mdc.putValue("correlationId", "c-1");
        mdc.putValue("clientDeviceId", "cd-1");
        mdc.putValue("userGuid", "u-1");
        mdc.putValue("deviceId", "d-1");
        return Log4jLogEvent.newBuilder()
            .setLoggerName(logger).setLevel(level).setMessage(message).setThrown(thrown)
            .setThreadName("main").setContextData(mdc).setTimeMillis(0L).build();
    }
}
