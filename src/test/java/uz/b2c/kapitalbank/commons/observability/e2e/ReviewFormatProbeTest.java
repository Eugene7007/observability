package uz.b2c.kapitalbank.commons.observability.e2e;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.message.StringMapMessage;
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
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import uz.b2c.kapitalbank.commons.observability.HttpClientLoggingInterceptor;
import uz.b2c.kapitalbank.commons.observability.HttpLogProperties;
import uz.b2c.kapitalbank.commons.observability.HttpLogSettings;
import uz.b2c.kapitalbank.commons.observability.HttpServerLoggingFilter;
import uz.b2c.kapitalbank.commons.observability.autoconfigure.ObservabilityAutoConfiguration;
import uz.b2c.kapitalbank.commons.observability.autoconfigure.ObservabilityStatusAutoConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ревью 2026-11-25: пути формата, которые LogFormatContractTest не покрывает. Тест фиксирует ФАКТИЧЕСКОЕ поведение
 * (находки ревью), чтобы оно было воспроизводимым; при исправлении находки соответствующее утверждение меняется.
 * Сырые строки печатаются в stderr.
 */
class ReviewFormatProbeTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ObservabilityAutoConfiguration.class, ObservabilityStatusAutoConfiguration.class})
    static class App {
    }

    @Test
    void uncoveredFormatPaths() throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties("spring.application.name=probe-service")
                .run();
            write();
            context.close();
            LogManager.shutdown();
        } finally {
            System.setOut(original);
            MDC.clear();
        }
        ObjectMapper strict = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        Map<String, JsonNode> byMarker = new HashMap<>();
        for (String line : buffer.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.startsWith("{")) {
                continue;
            }
            System.err.println("PROBE " + (line.length() > 600 ? line.substring(0, 600) + "...(" + line.length() + ")" : line));
            JsonNode record = strict.readTree(line); // падает на дублирующемся ключе
            String msg = record.path("message").asText();
            byMarker.put(msg.startsWith("HTTP in POST /probe/fail") ? "in-fail"
                : msg.startsWith("HTTP in POST /probe/big") ? "in-big"
                : msg.startsWith("HTTP out") ? "out-fail" : msg, record);
        }

        // 1. MDC пуст — поля трассировки отсутствуют (README: «поле без значения не пишется»)
        JsonNode plain = byMarker.get("probe-no-mdc");
        assertThat(plain.has("trace_id")).isFalse();

        // 2. Исключение без сообщения: error.message = null, вопреки README «null в выводе не бывает»
        JsonNode npe = byMarker.get("probe-npe");
        assertThat(npe.has("error.message")).isTrue();
        assertThat(npe.get("error.message").isNull()).as("FINDING: error.message null").isTrue();

        // 3. http.in с исключением: поле error.type есть, в эталоне контракта для http.in его нет
        JsonNode inFail = byMarker.get("in-fail");
        assertThat(inFail.path("error.type").asText()).isEqualTo("java.lang.IllegalStateException");
        assertThat(inFail.path("http.response.status_code").asInt()).isEqualTo(500);

        // 4. http.out с IOException: нет status_code и полей тела ответа
        JsonNode outFail = byMarker.get("out-fail");
        assertThat(outFail.has("http.response.status_code")).isFalse();
        assertThat(outFail.has("http.response.body.content")).isFalse();
        assertThat(outFail.path("error.type").asText()).isEqualTo("java.net.ConnectException");

        // 5. max-body-bytes=16384 (совет README для SOAP): layout всё равно режет строку до 8192 символов
        JsonNode big = byMarker.get("in-big");
        String content = big.path("http.request.body.content").asText();
        assertThat(content.length()).as("FINDING: maxStringLength=8192 режет тело").isLessThan(8200);
        System.err.println("PROBE content length=" + content.length() + " tail=" + content.substring(content.length() - 20));
        assertThat(content).doesNotEndWith("...[truncated]");

        // 6. Не-JSON-безопасное содержимое экранируется корректно
        assertThat(byMarker.get("probe-ctl\"\u0001\n\t\\").path("level").asText()).isEqualTo("INFO");

        // 7. Обычный MapMessage даёт поля data.* — их нет ни в контракте, ни в README «Формат логов»
        JsonNode map = byMarker.values().stream().filter(r -> r.has("data.amount")).findFirst().orElseThrow();
        assertThat(map.get("data.amount").isTextual()).isTrue();
    }

    private static void write() throws Exception {
        Logger log = LoggerFactory.getLogger("probe-app");
        MDC.clear();
        log.info("probe-no-mdc");
        log.error("probe-npe", new NullPointerException());
        log.info("probe-ctl\"\u0001\n\t\\");
        org.apache.logging.log4j.LogManager.getLogger("probe-map")
            .info(new StringMapMessage().with("amount", "100").with("phone", "998901234567"));

        HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/probe/fail");
        request.setContentType("application/json");
        request.setContent("{\"amount\":100}".getBytes(StandardCharsets.UTF_8));
        try {
            new HttpServerLoggingFilter(settings).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                req.getInputStream().readAllBytes();
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // без настоящего Tomcat нет ERROR-диспетчеризации: запись ждёт страницу ошибки, завершаем вручную
            HttpServerLoggingFilter.completeRequest(request);
        }

        HttpLogProperties bigProps = new HttpLogProperties();
        bigProps.setMaxBodyBytes(16384);
        HttpLogSettings bigSettings = new HttpLogSettings(bigProps);
        StringBuilder xml = new StringBuilder("<Envelope><Body>");
        while (xml.length() < 12000) {
            xml.append("<row><TRAN_AMT>150000</TRAN_AMT><RESPONSE_CODE>0</RESPONSE_CODE></row>");
        }
        xml.append("</Body></Envelope>");
        MockHttpServletRequest bigRequest = new MockHttpServletRequest("POST", "/probe/big");
        bigRequest.setContentType("text/xml");
        bigRequest.setContent(xml.toString().getBytes(StandardCharsets.UTF_8));
        new HttpServerLoggingFilter(bigSettings).doFilter(bigRequest, new MockHttpServletResponse(), (req, res) -> {
            req.getInputStream().readAllBytes();
            ((HttpServletResponse) res).setStatus(502);
        });

        MockClientHttpRequest outgoing = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://probe.internal/pay"));
        try {
            new HttpClientLoggingInterceptor(settings).intercept(outgoing, "{}".getBytes(StandardCharsets.UTF_8),
                (req, body) -> {
                    throw new java.net.ConnectException("refused");
                });
        } catch (IOException expected) {
            // ожидаемо
        }
        List.of().size();
    }
}
