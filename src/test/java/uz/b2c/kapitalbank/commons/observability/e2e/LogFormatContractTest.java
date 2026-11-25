package uz.b2c.kapitalbank.commons.observability.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
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
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Контракт формата логов: имена и типы полей JSON-записи в stdout, на которые опираются DevOps (Vector,
 * хранилище, дашборды, алерты). Эталон — {@code src/test/resources/log-format-contract.json}.
 * <p>
 * Тест пишет через настоящий log4j2 библиотеки по одной записи каждого вида с максимальным набором полей
 * и сравнивает их с эталоном: пропало поле, сменилось имя или тип — тест падает. Новое поле тоже роняет тест,
 * чтобы добавление было осознанным: поле вносится в эталон и в раздел «Формат логов» README.
 * Внутри 1.x формат меняется только добавлением полей; переименование, удаление и смена типа — только
 * в новой мажорной версии с предупреждением DevOps.
 */
class LogFormatContractTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ObservabilityAutoConfiguration.class, ObservabilityStatusAutoConfiguration.class})
    static class App {
    }

    @Test
    void outputMatchesTheContract() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties("spring.application.name=contract-service")
                .run();
            writeRecordOfEveryKind();
            context.close();
            LogManager.shutdown();
        } finally {
            System.setOut(original);
            MDC.clear();
        }

        Map<String, Map<String, String>> actual = new TreeMap<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String line : buffer.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode record = mapper.readTree(line);
            if (record.path("logger").asText().startsWith("observability.http.")
                || record.path("message").asText().startsWith("contract-")) {
                actual.put(kindOf(record), shape(record));
            }
        }

        Map<String, Map<String, String>> expected = expected(mapper);
        List<String> problems = new ArrayList<>();
        expected.forEach((kind, fields) -> {
            Map<String, String> got = actual.get(kind);
            if (got == null) {
                problems.add(kind + ": запись не найдена");
                return;
            }
            fields.forEach((name, type) -> {
                if (!got.containsKey(name)) {
                    problems.add(kind + ": нет поля " + name);
                } else if (!got.get(name).equals(type)) {
                    problems.add(kind + ": поле " + name + " — " + got.get(name) + " вместо " + type);
                }
            });
            got.keySet().stream().filter(name -> !fields.containsKey(name))
                .forEach(name -> problems.add(kind + ": новое поле " + name + " (" + got.get(name) + ")"
                    + " — внесите его в log-format-contract.json и README"));
        });
        if (!problems.isEmpty()) {
            fail("Формат логов разошёлся с контрактом:\n  " + String.join("\n  ", problems)
                + "\nФактический формат:\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual));
        }
        assertThat(actual.keySet()).containsAll(expected.keySet());
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

        HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/contract-in/7");
        request.setQueryString("lang=ru");
        request.setContentType("application/json");
        request.setContent("{\"amount\":100}".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Accept-Language", "ru-RU");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/contract-in/{id}");
        new HttpServerLoggingFilter(settings).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            req.getInputStream().readAllBytes();
            HttpServletResponse response = (HttpServletResponse) res;
            response.setStatus(422);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"LIMIT\"}");
        });

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
    }

    private static String kindOf(final JsonNode record) {
        String logger = record.path("logger").asText();
        String kind;
        if ("observability.http.in".equals(logger)) {
            kind = "http.in";
        } else if ("observability.http.out".equals(logger)) {
            kind = "http.out";
        } else if (record.has("error.type")) {
            kind = "error";
        } else {
            kind = "log";
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
        try (InputStream in = LogFormatContractTest.class.getResourceAsStream("/log-format-contract.json")) {
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
