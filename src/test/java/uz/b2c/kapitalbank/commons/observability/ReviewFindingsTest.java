package uz.b2c.kapitalbank.commons.observability;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestTemplate;
import uz.b2c.kapitalbank.commons.observability.kafka.KafkaCorrelationInterceptors;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессионные тесты на находки независимого ревью: каждая проверка воспроизводит сценарий из отчёта.
 */
class ReviewFindingsTest {

    private static final String PAN = "8600123456781233";
    private final MaskingRules rules = MaskingRules.base();

    // ---------- блокер 1: длинные значения ----------

    @Test
    void longValuesDoNotOverflowStackAndAreMaskedInLinearTime() {
        String jwt = "x".repeat(200_000);
        for (String text : List.of("\"accessToken\":\"" + jwt + "\"", "fullName=" + "Ivan ".repeat(40_000),
            "\"token\":\"" + jwt, "pan: " + "1".repeat(100_000))) {
            long start = System.nanoTime();
            String masked = LogTextMasker.mask(text, rules);
            long micros = (System.nanoTime() - start) / 1000;
            assertThat(masked.length()).isLessThanOrEqualTo(LogTextMasker.MAX_LENGTH + 32);
            assertThat(micros).isLessThan(500_000);
        }
    }

    @Test
    void maskingFailureNeverReturnsOriginalText() {
        assertThat(LogTextMasker.maskOrPlaceholder(null)).isNull();
        assertThat(LogTextMasker.maskOrPlaceholder("cvv=123")).isEqualTo("cvv=***");
    }

    // ---------- блокер 3: обходы маскирования ----------

    @Test
    void compoundAndSnakeCaseNamesAreRecognised() {
        String text = "senderCardNumber=" + PAN + ", card_number=" + PAN + ", newPassword=Qwerty1, clientSecret=abc,"
            + " card_cvv=123, pinCode=1234, otpCode=555111, phone_number=998901234567, mobilePhone=998901234567,"
            + " passportNumber=AA1234567, holderName=IVAN IVANOV, access_token=t1, refresh_token=t2";

        String masked = LogTextMasker.mask(text, rules);

        assertThat(masked).doesNotContain(PAN).doesNotContain("Qwerty1").doesNotContain("abc,")
            .doesNotContain("123,").doesNotContain("555111").doesNotContain("998901234567")
            .doesNotContain("AA1234567").doesNotContain("IVANOV").doesNotContain("t1").doesNotContain("t2");
    }

    @Test
    void panWithSeparatorsPhonesAndBearerAreMasked() {
        assertThat(LogTextMasker.mask("card 8600 1234 5678 1233 ok", rules)).isEqualTo("card 8600 12** **** 1233 ok");
        assertThat(LogTextMasker.mask("card 8600-1234-5678-1233", rules)).isEqualTo("card 8600-12**-****-1233");
        assertThat(LogTextMasker.mask("SMS to +998901234567 sent", rules)).isEqualTo("SMS to +********4567 sent");
        assertThat(LogTextMasker.mask("SMS to 998 90 123 45 67", rules)).doesNotContain("123 45");
        assertThat(LogTextMasker.mask("Authorization: Bearer eyJhbGciOi.x.y", rules)).doesNotContain("eyJ");
        assertThat(LogTextMasker.mask("header Bearer eyJhbGciOi.x.y", rules)).isEqualTo("header Bearer ***");
    }

    @Test
    void escapedJsonAndXmlAreMasked() {
        assertThat(LogTextMasker.mask("payload=\"{\\\"cvv\\\":\\\"123\\\",\\\"pinfl\\\":\\\"31234567890123\\\"}\"", rules))
            .doesNotContain("123\\\"").doesNotContain("31234567890123");
        assertThat(LogTextMasker.mask("<req><cvv>123</cvv><pinfl>31234567890123</pinfl></req>", rules))
            .isEqualTo("<req><cvv>***</cvv><pinfl>***</pinfl></req>");
        assertThat(LogTextMasker.mask("&lt;ns:cvv&gt;123&lt;/ns:cvv&gt;", rules)).isEqualTo("&lt;ns:cvv&gt;***&lt;/ns:cvv&gt;");
    }

    @Test
    void jsonBodyMasksNumericPanUnnamedFieldsKeysAndNestedJson() {
        JsonBodyMasker masker = new JsonBodyMasker(rules);
        byte[] body = ("{\"x\":" + PAN + ",\"balances\":{\"998901234567\":10},"
            + "\"payload\":\"{\\\"cvv\\\":\\\"123\\\"}\",\"code\":\"CARD_EXPIRED\",\"errorCode\":40012}")
            .getBytes(StandardCharsets.UTF_8);

        String masked = masker.mask(body, body.length, false);

        assertThat(masked).doesNotContain(PAN).doesNotContain("998901234567").doesNotContain("\\\"123\\\"")
            .contains("\"code\":\"CARD_EXPIRED\"").contains("\"errorCode\":40012");
    }

    // ---------- важно 5: команда не может ослабить базу ----------

    @Test
    void teamRulesCannotWeakenBaseRules() {
        HttpLogProperties.Masking masking = new HttpLogProperties.Masking();
        masking.setCodes(Set.of("cvv", "pinfl", "cardNumber"));
        JsonBodyMasker masker = new JsonBodyMasker(masking);
        byte[] body = ("{\"cvv\":123,\"pinfl\":31234567890123,\"cardNumber\":" + PAN + "}").getBytes(StandardCharsets.UTF_8);

        assertThat(masker.mask(body, body.length, false))
            .isEqualTo("{\"cvv\":\"***\",\"pinfl\":\"***\",\"cardNumber\":\"860012******1233\"}");
    }

    @Test
    void secretHeadersAreNeverLoggedEvenIfAllowed() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setAllowedHeaders(Set.of("authorization", "cookie", "userpinfl", "x-request-id"));
        HttpLogSettings settings = new HttpLogSettings(properties);

        HttpLogMessage message = new HttpLogMessage("test");
        settings.addRequestHeaders(message, List.of("Authorization", "Cookie", "UserPinfl", "X-Request-ID"),
            name -> List.of("X-Request-ID".equals(name) ? "r-1" : "secret"));

        // поле на заголовок по OpenTelemetry: http.request.header.<имя в нижнем регистре>, значение — массив
        assertThat(message.getData()).containsOnly(Map.entry("http.request.header.x-request-id", List.of("r-1")));
    }

    // ---------- важно 7 и 9: async и context-path ----------

    @Test
    void asyncRequestIsLoggedOnCompletionWithFinalStatus() throws Exception {
        HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
        HttpServerLoggingFilter filter = new HttpServerLoggingFilter(new HttpLogSettings(new HttpLogProperties()), sink);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/pay");
        request.setAsyncSupported(true);
        request.setContentType("application/json");
        request.setContent("{\"cvv\":\"123\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            req.getInputStream().readAllBytes();
            req.startAsync(req, res);
        });
        assertThat(sink.events).isEmpty();

        response.setStatus(500);
        ((MockAsyncContext) request.getAsyncContext()).complete();

        HttpLogMessage message = sink.single(Level.ERROR);
        assertThat(message.get("http.response.status_code")).isEqualTo("500");
        assertThat(message.get("http.request.body.content")).isEqualTo("{\"cvv\":\"***\"}");
    }

    @Test
    void excludedPathsIgnoreContextPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/svc/actuator/health");
        request.setContextPath("/svc");
        HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

        assertThat(settings.isExcluded(HttpServerLoggingFilter.pathWithinApplication(request))).isTrue();
        assertThat(settings.isExcluded("/internal/v1/anti-fraud/telecom/registration/send-otp")).isTrue();
    }

    // ---------- важно 10: исходящие вызовы ----------

    @Test
    void outgoingUrlHasNoCredentialsAndLongNumbersInPath() {
        assertThat(LogTextMasker.maskPath("/clients/31234567890123/cards/" + PAN))
            .isEqualTo("/clients/***/cards/860012******1233");
    }

    @Test
    void userHeadersCanBeLimitedToInternalHosts() throws Exception {
        CorrelationInterceptor interceptor = new CorrelationInterceptor(List.of("*.svc.cluster.local", "limits"));
        MDC.put(Correlation.USER_GUID, "u-1");
        try {
            MockClientHttpRequest external = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://partner.uz/x"));
            interceptor.intercept(external, new byte[0], (req, body) -> null);
            MockClientHttpRequest internal = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://limits:8080/x"));
            interceptor.intercept(internal, new byte[0], (req, body) -> null);

            assertThat(external.getHeaders().getFirst(Correlation.USER_GUID_HEADER)).isNull();
            assertThat(external.getHeaders().getFirst(Correlation.CORRELATION_ID)).isNotNull();
            assertThat(internal.getHeaders().getFirst(Correlation.USER_GUID_HEADER)).isEqualTo("u-1");
        } finally {
            MDC.clear();
        }
    }

    // ---------- важно 11: замена библиотеки без правок кода ----------

    @Test
    void restTemplateBeansCreatedWithNewGetInterceptors() {
        RestTemplateLoggingInstaller installer = new RestTemplateLoggingInstaller(
            CorrelationInterceptor::new, () -> new HttpClientLoggingInterceptor(new HttpLogSettings(new HttpLogProperties())));
        RestTemplate restTemplate = new RestTemplate();

        installer.postProcessAfterInitialization(restTemplate, "plainRestTemplate");
        installer.postProcessAfterInitialization(restTemplate, "plainRestTemplate");

        assertThat(restTemplate.getInterceptors()).hasSize(2);
        assertThat(restTemplate.getInterceptors().get(0)).isInstanceOf(CorrelationInterceptor.class);
    }

    @Test
    void kafkaCarriesCorrelationAndCleansMdc() {
        MDC.put(Correlation.CORRELATION_ID, "c-42");
        ProducerRecord<String, String> sent = new KafkaCorrelationInterceptors.Producing<String, String>()
            .onSend(new ProducerRecord<>("topic", "k", "v"));
        MDC.clear();
        assertThat(new String(sent.headers().lastHeader(Correlation.CORRELATION_ID).value(), StandardCharsets.UTF_8))
            .isEqualTo("c-42");

        ConsumerRecord<String, String> received = new ConsumerRecord<>("topic", 0, 0, "k", "v");
        sent.headers().forEach(header -> received.headers().add(header));
        KafkaCorrelationInterceptors.Consuming<String, String> consuming = new KafkaCorrelationInterceptors.Consuming<>();
        consuming.intercept(received, null);
        assertThat(MDC.get(Correlation.CORRELATION_ID)).isEqualTo("c-42");
        consuming.afterRecord(received, null);
        assertThat(MDC.get(Correlation.CORRELATION_ID)).isNull();
    }
}
