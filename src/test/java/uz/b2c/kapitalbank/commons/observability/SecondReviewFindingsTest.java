package uz.b2c.kapitalbank.commons.observability;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.listener.CompositeRecordInterceptor;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.util.ReflectionUtils;
import uz.b2c.kapitalbank.commons.observability.kafka.KafkaCorrelationPostProcessor;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессионные тесты на находки второго независимого ревью.
 */
class SecondReviewFindingsTest {

    private final MaskingRules rules = MaskingRules.base();

    @Test
    void cardNumbersFollowedByOtherNumbersAreMasked() {
        assertThat(LogTextMasker.mask("transfer 8600123456781233 1000000 UZS", rules))
            .isEqualTo("transfer 860012******1233 1000000 UZS");
        assertThat(LogTextMasker.mask("cards 4111111111111111 5555555555554444", rules))
            .isEqualTo("cards 411111******1111 555555******4444");
        assertThat(LogTextMasker.mask("pan 4111 1111 1111 1111 2024", rules)).isEqualTo("pan 4111 11** **** 1111 2024");
        assertThat(LogTextMasker.mask("phone 998901234567 100", rules)).isEqualTo("phone ********4567 100");
        assertThat(LogTextMasker.mask("tel +998 (90) 123-45-67", rules)).doesNotContain("123");
        assertThat(LogTextMasker.mask("карта8600123456781233", rules)).isEqualTo("карта860012******1233");
    }

    @Test
    void fuzzLeaksNoCardNumbers() {
        Random random = new Random(42);
        String[] noise = {" ", ",", "-", ":", "=", "\"", "{", "}", "(", ")", "x", "Я", "1", "12", "2024", "\n", "/"};
        String pan = "4111111111111111";
        for (int round = 0; round < 20_000; round++) {
            StringBuilder text = new StringBuilder();
            for (int k = random.nextInt(6); k > 0; k--) {
                text.append(noise[random.nextInt(noise.length)]);
            }
            // за номером — любой не цифровой символ: 20 цифр подряд уже не номер карты
            text.append(' ').append(pan).append(noise[random.nextInt(4)]);
            for (int k = random.nextInt(6); k > 0; k--) {
                text.append(noise[random.nextInt(noise.length)]);
            }
            String masked = LogTextMasker.mask(text.toString(), rules);
            assertThat(masked).as(text.toString()).doesNotContain(pan);
        }
    }

    @Test
    void urlAndCodeValuesAreScannedInside() {
        assertThat(LogTextMasker.mask("url=http://svc/clients/998901234567/cards/8600123456781233?x=1", rules))
            .isEqualTo("url=http://svc/clients/***/cards/860012******1233?***");
        assertThat(LogTextMasker.mask("code=CARD 8600123456781233", rules)).doesNotContain("8600123456781233");
    }

    @Test
    void oneTimeCodesAreHiddenInAnyForm() {
        assertThat(LogTextMasker.mask("verificationCode=482913, confirm_code: 123456", rules))
            .isEqualTo("verificationCode=***, confirm_code: ***");
        JsonBodyMasker masker = new JsonBodyMasker(rules);
        byte[] body = "{\"verificationCode\":482913,\"code\":40012,\"otpCode\":\"5521\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(masker.mask(body, body.length, false))
            .isEqualTo("{\"verificationCode\":\"***\",\"code\":40012,\"otpCode\":\"***\"}");
    }

    @Test
    void dictionaryCoversDocumentsBirthEmailAndPersonNames() {
        JsonBodyMasker masker = new JsonBodyMasker(rules);
        byte[] body = ("{\"card\":{\"holder\":\"IVAN IVANOV\"},\"client\":{\"name\":\"Ivan Ivanov\",\"birthday\":\"1990-01-01\","
            + "\"dateOfBirth\":\"1990-01-01\",\"documentNumber\":\"AA1234567\",\"jshshir\":\"31234567890123\","
            + "\"email\":\"ivan@mail.uz\",\"address\":\"Tashkent\"},\"product\":{\"name\":\"Deposit\"}}")
            .getBytes(StandardCharsets.UTF_8);

        String masked = masker.mask(body, body.length, false);

        assertThat(masked).doesNotContain("IVANOV").doesNotContain("Ivanov").doesNotContain("1990")
            .doesNotContain("AA1234567").doesNotContain("31234567890123").doesNotContain("Tashkent")
            .contains("\"email\":\"i***@mail.uz\"").contains("\"name\":\"Deposit\"");
    }

    @Test
    void codeObjectsDoNotCauseQuadraticTime() {
        String text = "code:[".repeat(5000);
        long start = System.nanoTime();
        LogTextMasker.mask(text, rules);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
        assertThat(LogTextMasker.mask("url=".repeat(8000), rules)).isNotNull();
    }

    @Test
    void truncatedNumbersDoNotLeakDigits() {
        JsonBodyMasker masker = new JsonBodyMasker(rules);
        byte[] body = "{\"a\":\"x\",\"ref\":8600123456781233}".getBytes(StandardCharsets.UTF_8);
        String masked = masker.mask(body, body.length - 7, true);
        assertThat(masked).doesNotContain("8600123456");
    }

    @Test
    void serviceRecordInterceptorIsKept() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        RecordInterceptor<String, String> own = (ConsumerRecord<String, String> record, Consumer<String, String> c) -> record;
        factory.setRecordInterceptor(own);

        new KafkaCorrelationPostProcessor().postProcessAfterInitialization(factory, "factory");
        new KafkaCorrelationPostProcessor().postProcessAfterInitialization(factory, "factory");

        Field field = ReflectionUtils.findField(factory.getClass(), "recordInterceptor");
        ReflectionUtils.makeAccessible(field);
        Object installed = ReflectionUtils.getField(field, factory);
        assertThat(installed).isInstanceOf(CompositeRecordInterceptor.class);
        assertThat(String.valueOf(ReflectionUtils.getField(
            ReflectionUtils.findField(CompositeRecordInterceptor.class, "delegates"), installed)))
            .contains("Consuming").contains("SecondReviewFindingsTest");
    }
}
