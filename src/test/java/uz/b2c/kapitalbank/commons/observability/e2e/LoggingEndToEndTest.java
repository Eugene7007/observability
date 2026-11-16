package uz.b2c.kapitalbank.commons.observability.e2e;

import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import uz.b2c.kapitalbank.commons.observability.autoconfigure.ObservabilityAutoConfiguration;
import uz.b2c.kapitalbank.commons.observability.autoconfigure.ObservabilityStatusAutoConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Настоящий запуск Spring Boot с библиотекой: выбор конфигурации log4j2 до старта логирования, режим Kafka
 * с недоступным брокером (старт и остановка не зависают), маскирование строк лога с полями команды.
 * Запускается в отдельной JVM: Spring Boot перенастраивает log4j2 глобально.
 */
class LoggingEndToEndTest {

    private static final String PAN = "8600123456781233";

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ObservabilityAutoConfiguration.class, ObservabilityStatusAutoConfiguration.class})
    static class App {
    }

    @Test
    void kafkaModeWithUnreachableBrokerStartsMasksAndStops() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        long startedAt = System.nanoTime();
        long startMs;
        long stopMs;
        try {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.NONE)
                .properties(
                    "spring.application.name=e2e-service",
                    "logging.config=classpath:service-own-log4j2.xml",
                    "logging.appender.kafka.bootstrap.servers=127.0.0.1:1",
                    "lib.observability.http-log.masking.hide=loyaltyId")
                .run();
            startMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            Logger log = LoggerFactory.getLogger("e2e");
            log.info("client card {} phone={} loyaltyId={}", PAN, "998901234567", "L-777");
            log.error("call failed", new IllegalStateException("400 Bad Request: {\"cvv\":\"123\"}"));
            long stopping = System.nanoTime();
            context.close();
            LogManager.shutdown();
            stopMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopping);
        } finally {
            System.setOut(original);
        }
        String out = buffer.toString(StandardCharsets.UTF_8);
        System.out.println("start " + startMs + " ms, stop " + stopMs + " ms");

        assertThat(out).contains("lib-observability: логирование JSON → STDOUT_AND_KAFKA")
            .contains("logging.config сервиса (classpath:service-own-log4j2.xml) не используется")
            .contains("\"service.name\":\"e2e-service\"")
            .contains("client card 860012******1233 phone=***4567 loyaltyId=***")
            .contains("400 Bad Request: {\\\"cvv\\\":\\\"***\\\"}")
            .doesNotContain(PAN).doesNotContain("998901234567").doesNotContain("L-777");
        assertThat(stopMs).isLessThan(10_000);
    }
}
