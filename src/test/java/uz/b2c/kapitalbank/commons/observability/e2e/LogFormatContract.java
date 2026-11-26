package uz.b2c.kapitalbank.commons.observability.e2e;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.message.StringMapMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import uz.b2c.kapitalbank.commons.observability.HttpClientLoggingInterceptor;
import uz.b2c.kapitalbank.commons.observability.HttpLogProperties;
import uz.b2c.kapitalbank.commons.observability.HttpLogSettings;
import uz.b2c.kapitalbank.commons.observability.HttpServerLoggingFilter;
import uz.b2c.kapitalbank.commons.observability.autoconfigure.ObservabilityAutoConfiguration;
import uz.b2c.kapitalbank.commons.observability.autoconfigure.ObservabilityStatusAutoConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.fail;

/**
 * Контракт формата логов: имена, типы и формат значений полей JSON-записи в stdout, на которые опираются DevOps
 * (Vector, хранилище, дашборды, алерты). Эталон — {@code src/test/resources/log-format-contract.json}.
 * <p>
 * Через настоящий log4j2 библиотеки пишется по одной записи каждого вида с максимальным набором полей, и они
 * сравниваются с эталоном: пропало поле, сменилось имя или тип, появилось новое поле, дубликат ключа — ошибка.
 * Дополнительно проверяются значения, которые разбирает конвейер: формат {@code @timestamp} (UTC), уровни,
 * уровень HTTP-записей по статусу. Новое поле вносится в эталон и в раздел «Формат логов» README.
 * <p>
 * Проверка идёт в каждой конфигурации вывода отдельным тестом ({@link LogFormatContractTest} — только stdout,
 * {@link LogFormatContractKafkaTest} — stdout и Kafka, как в 1.x на проде): stdout-аппендер описан в каждой из них.
 * Режим и формат задаются тестом явно, чтобы переменные окружения сборочного агента не подменили конфигурацию.
 */
abstract class LogFormatContract {

    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL");
    private static final Map<String, String> EXPECTED_LEVEL = Map.of(
        "log", "INFO", "error", "ERROR", "map", "INFO",
        "http.in", "WARN", "http.in.failed", "ERROR", "http.out", "ERROR", "http.out.failed", "ERROR");

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ObservabilityAutoConfiguration.class, ObservabilityStatusAutoConfiguration.class})
    static class App {
    }

    /** Пишет записи всех видов в заданной конфигурации и сверяет их с эталоном. */
    static void checkContract(final String... properties) throws Exception {
        java.time.Instant started = java.time.Instant.now();
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties("spring.application.name=contract-service", "lib.observability.log.format=JSON")
                .properties(properties)
                .run();
            writeRecordOfEveryKind();
            context.close();
            LogManager.shutdown();
        } finally {
            System.setOut(original);
            MDC.clear();
        }

        // дубликат ключа в одной записи — тоже нарушение контракта, поэтому разбор строгий
        ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        Map<String, JsonNode> records = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        for (String line : buffer.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode record;
            try {
                record = mapper.readTree(line);
            } catch (IOException e) {
                problems.add("запись не разбирается строго (" + e.getMessage().split("\n")[0] + "): " + line);
                continue;
            }
            String kind = kindOf(record);
            if (kind != null) {
                records.put(kind, record);
            }
            checkSpecial(record, problems);
        }

        Map<String, Map<String, String>> expected = expected(mapper);
        Map<String, Map<String, String>> actual = new TreeMap<>();
        expected.forEach((kind, fields) -> {
            JsonNode record = records.get(kind);
            if (record == null) {
                problems.add(kind + ": запись не найдена");
                return;
            }
            Map<String, String> got = shape(record);
            actual.put(kind, got);
            compare(kind, fields, got, problems);
            checkValues(kind, record, started, problems);
        });
        records.forEach((kind, record) -> {
            if (!expected.containsKey(kind)) {
                problems.add(kind + ": вида записи нет в эталоне — внесите его в log-format-contract.json и README");
                actual.put(kind, shape(record));
            }
        });
        if (!problems.isEmpty()) {
            fail("Формат логов разошёлся с контрактом:\n  " + String.join("\n  ", problems)
                + "\nФактический формат:\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual));
        }
    }

    private static void compare(final String kind, final Map<String, String> fields, final Map<String, String> got,
                                final List<String> problems) {
        fields.forEach((name, type) -> {
            if (name.endsWith(".*")) {
                String prefix = name.substring(0, name.length() - 1);
                if (got.keySet().stream().noneMatch(key -> key.startsWith(prefix))) {
                    problems.add(kind + ": нет полей " + name);
                }
            } else if (!got.containsKey(name)) {
                problems.add(kind + ": нет поля " + name);
            } else if (!got.get(name).equals(type)) {
                problems.add(kind + ": поле " + name + " — " + got.get(name) + " вместо " + type);
            }
        });
        got.forEach((name, type) -> {
            String pattern = fields.keySet().stream()
                .filter(key -> key.endsWith(".*") && name.startsWith(key.substring(0, key.length() - 1)))
                .findFirst().orElse(null);
            if (pattern != null) {
                if (!fields.get(pattern).equals(type)) {
                    problems.add(kind + ": поле " + name + " — " + type + " вместо " + fields.get(pattern));
                }
            } else if (!fields.containsKey(name)) {
                problems.add(kind + ": новое поле " + name + " (" + type + ") — внесите его в log-format-contract.json"
                    + " и README");
            }
        });
    }

    /** Исключение без сообщения и тело на пределе max-body-bytes. */
    private static void checkSpecial(final JsonNode record, final List<String> problems) {
        String message = record.path("message").asText();
        record.fields().forEachRemaining(field -> {
            if (field.getValue().isNull()) {
                problems.add("«" + message + "»: поле " + field.getKey() + " = null (поле без значения не пишется)");
            }
        });
        if ("contract-npe".equals(message) && record.has("error.message")) {
            problems.add("contract-npe: у исключения без сообщения есть error.message");
        }
        if (message.contains("contract-large")) {
            String content = record.path("http.request.body.content").asText();
            if (!content.endsWith("...[truncated]") || content.length() < 65536) {
                problems.add("contract-large: тело на 64 КБ обрезано не библиотекой (длина " + content.length()
                    + ", конец «" + content.substring(Math.max(0, content.length() - 20)) + "»)");
            }
        }
    }

    /** Значения, которые разбирает конвейер: время в UTC, уровни, уровень HTTP-записи по статусу. */
    private static void checkValues(final String kind, final JsonNode record, final java.time.Instant started,
                                    final List<String> problems) {
        String timestamp = record.path("@timestamp").asText();
        if (!TIMESTAMP.matcher(timestamp).matches()) {
            problems.add(kind + ": @timestamp «" + timestamp + "» не в формате yyyy-MM-ddTHH:mm:ss.SSSZ (UTC)");
        } else {
            // «Z» в шаблоне — буква, поэтому пояс проверяется по самому времени: запись сделана только что
            java.time.Instant written = java.time.Instant.parse(timestamp);
            if (written.isBefore(started.minusSeconds(60)) || written.isAfter(java.time.Instant.now().plusSeconds(60))) {
                problems.add(kind + ": @timestamp " + timestamp + " не UTC (расходится с текущим временем UTC)");
            }
        }
        String level = record.path("level").asText();
        if (!LEVELS.contains(level)) {
            problems.add(kind + ": уровень «" + level + "» не из набора " + LEVELS);
        } else if (!level.equals(EXPECTED_LEVEL.get(kind))) {
            problems.add(kind + ": уровень " + level + " вместо " + EXPECTED_LEVEL.get(kind));
        }
    }

    /** Записи всех видов, каждая с максимальным набором полей. */
    private static void writeRecordOfEveryKind() throws Exception {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");
        MDC.put("correlationId", "c0ffee00-0000-4000-8000-000000000001");
        MDC.put("clientDeviceId", "device-1");
        MDC.put("userGuid", "user-1");
        MDC.put("deviceId", "device-1");

        Logger log = LoggerFactory.getLogger("contract-app");
        log.info("contract-log");
        log.error("contract-error", new IllegalStateException("boom"));
        log.error("contract-npe", new NullPointerException());
        LogManager.getLogger("contract-map").info(new StringMapMessage().with("orderId", "42").with("status", "NEW"));

        HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

        // входящий 4xx с телами
        MockHttpServletRequest request = request("/api/v1/contract-in/7");
        new HttpServerLoggingFilter(settings).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            req.getInputStream().readAllBytes();
            HttpServletResponse response = (HttpServletResponse) res;
            response.setStatus(422);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"LIMIT\"}");
        });

        // тело больше максимального max-body-bytes (64 КБ): обрезает библиотека, а не log4j
        HttpLogProperties largeProperties = new HttpLogProperties();
        largeProperties.setMaxBodyBytes(HttpLogProperties.MAX_BODY_BYTES);
        MockHttpServletRequest large = request("/api/v1/contract-large/7");
        large.setContent(("[" + "{\"amount\":100,\"status\":\"OK\"},".repeat(3_000) + "{}]")
            .getBytes(StandardCharsets.UTF_8));
        new HttpServerLoggingFilter(new HttpLogSettings(largeProperties)).doFilter(large, new MockHttpServletResponse(),
            (req, res) -> {
                req.getInputStream().readAllBytes();
                ((HttpServletResponse) res).setStatus(400);
            });

        // входящий с исключением: без контейнера нет прохода ERROR, запись завершается вручную
        MockHttpServletRequest failing = request("/api/v1/contract-failed/7");
        try {
            new HttpServerLoggingFilter(settings).doFilter(failing, new MockHttpServletResponse(), (req, res) -> {
                req.getInputStream().readAllBytes();
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            HttpServerLoggingFilter.completeRequest(failing);
        }

        // исходящий 5xx с телами
        MockClientHttpRequest outgoing = new MockClientHttpRequest(HttpMethod.POST, URI.create(
            "http://contract-out.internal/api/v1/pay?lang=ru"));
        outgoing.getHeaders().add("Content-Type", "application/json");
        MockClientHttpResponse wire = new MockClientHttpResponse(
            "{\"code\":\"DECLINED\"}".getBytes(StandardCharsets.UTF_8), HttpStatus.BAD_GATEWAY);
        wire.getHeaders().add("Content-Type", "application/json");
        ClientHttpResponse response = new HttpClientLoggingInterceptor(settings)
            .intercept(outgoing, "{\"amount\":100}".getBytes(StandardCharsets.UTF_8), (req, body) -> wire);
        try (InputStream body = response.getBody()) {
            body.readAllBytes();
        }
        response.close();

        // исходящий, упавший до ответа
        MockClientHttpRequest refused = new MockClientHttpRequest(HttpMethod.POST, URI.create(
            "http://contract-refused.internal/api/v1/pay"));
        refused.getHeaders().add("Content-Type", "application/json");
        try {
            new HttpClientLoggingInterceptor(settings).intercept(refused,
                "{\"amount\":100}".getBytes(StandardCharsets.UTF_8), (req, body) -> {
                    throw new ConnectException("refused");
                });
        } catch (IOException expected) {
            // ожидаемо
        }
    }

    private static MockHttpServletRequest request(final String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setQueryString("lang=ru");
        request.setContentType("application/json");
        request.setContent("{\"amount\":100}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Accept-Language", "ru-RU");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, path.replace("/7", "/{id}"));
        return request;
    }

    private static String kindOf(final JsonNode record) {
        String logger = record.path("logger").asText();
        String message = record.path("message").asText();
        String kind = null;
        if (message.contains("contract-large") || "contract-npe".equals(message)) {
            kind = null;
        } else if ("observability.http.in".equals(logger)) {
            kind = message.contains("contract-failed") ? "http.in.failed" : "http.in";
        } else if ("observability.http.out".equals(logger)) {
            kind = message.contains("contract-refused") ? "http.out.failed" : "http.out";
        } else if ("contract-map".equals(logger)) {
            kind = "map";
        } else if (message.startsWith("contract-")) {
            kind = record.has("error.type") ? "error" : "log";
        }
        return kind;
    }

    /** Поле → тип JSON: string, number, boolean, object, array. */
    private static Map<String, String> shape(final JsonNode record) {
        Map<String, String> shape = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = record.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            String type = value.isTextual() ? "string" : value.isNumber() ? "number" : value.isBoolean() ? "boolean"
                : value.isObject() ? "object" : value.isArray() ? "array" : "null";
            shape.put(field.getKey(), type);
        }
        return shape;
    }

    /** Эталон: общие поля плюс поля вида записи. */
    private static Map<String, Map<String, String>> expected(final ObjectMapper mapper) throws Exception {
        Map<String, Map<String, String>> expected = new TreeMap<>();
        try (InputStream in = LogFormatContract.class.getResourceAsStream("/log-format-contract.json")) {
            if (in == null) {
                fail("Нет эталона src/test/resources/log-format-contract.json");
            }
            JsonNode contract = mapper.readTree(in);
            Map<String, String> common = new TreeMap<>();
            contract.path("common").fields().forEachRemaining(f -> common.put(f.getKey(), f.getValue().asText()));
            contract.path("records").fields().forEachRemaining(kind -> {
                Map<String, String> fields = new TreeMap<>(common);
                kind.getValue().fields().forEachRemaining(f -> fields.put(f.getKey(), f.getValue().asText()));
                expected.put(kind.getKey(), fields);
            });
        }
        return expected;
    }
}
