package uz.b2c.kapitalbank.commons.observability.log4j;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запускает настоящие конфигурации log4j2 из библиотеки и проверяет, что в stdout не попадают ПДн.
 */
class MaskedLayoutTest {

    private static final String PAN = "8600123456781233";

    @Test
    void jsonConfigMasksMessagesAndExceptions() {
        String out = runWith("classpath:lib-observability/log4j2-json.xml", logger -> {
            logger.info("client card {} phone={}", PAN, "998901234567");
            logger.error("call failed", new IllegalStateException("400 Bad Request: {\"pan\":\"" + PAN + "\"}",
                new RuntimeException("cause with token=secret-value")));
        });

        assertThat(out).doesNotContain(PAN).doesNotContain("998901234567").doesNotContain("secret-value")
            .contains("\"message\":\"client card 860012******1233 phone=***4567\"")
            .contains("\"exception.type\":\"java.lang.IllegalStateException\"")
            .contains("\"exception.message\":\"400 Bad Request: {\\\"pan\\\":\\\"860012******1233\\\"}\"")
            .contains("Caused by: java.lang.RuntimeException: cause with token=***");
        // вне Spring-контекста log4j пишет в консоль предупреждения о lookup spring:, их пропускаем
        long jsonLines = out.lines().filter(line -> line.startsWith("{")).peek(line ->
            assertThat(line).endsWith("}")).count();
        assertThat(jsonLines).isEqualTo(2);
    }

    @Test
    void mapMessageFieldsAreMaskedAndDoNotOverrideMessage() {
        String out = runWith("classpath:lib-observability/log4j2-json.xml", logger -> logger.info(
            new org.apache.logging.log4j.message.StringMapMessage()
                .with("cardNumber", "8600 1234 5678 1233").with("phone", "998901234567").with("message", "m")));

        assertThat(out).doesNotContain("1234 5678").doesNotContain("998901234567")
            .contains("\"uz.kapitalbank.data.phone\":\"***4567\"").contains("\"uz.kapitalbank.data.message\":\"m\"");
    }

    @Test
    void legacyKafkaFormatDoesNotLeakArbitraryMdc() {
        org.apache.logging.log4j.ThreadContext.put("pinfl", "31234567890123");
        org.apache.logging.log4j.ThreadContext.put("correlationId", "c-1");
        try {
            String out = runWith("classpath:legacy-layout-test.xml", logger -> logger.info("legacy"));

            assertThat(out).doesNotContain("31234567890123").contains("\"correlationId\":\"c-1\"");
        } finally {
            org.apache.logging.log4j.ThreadContext.clearAll();
        }
    }

    @Test
    void textConfigMasksMessages() {
        String out = runWith("classpath:lib-observability/log4j2-text.xml",
            logger -> logger.info("card pan={}", PAN));

        assertThat(out).contains("card pan=860012******1233").doesNotContain(PAN);
    }

    private static String runWith(final String config, final java.util.function.Consumer<Logger> body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        LoggerContext context = Configurator.initialize("masked-layout-test", config);
        try {
            body.accept(context.getLogger("test.masking"));
        } finally {
            Configurator.shutdown(context);
            System.setOut(original);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

}
