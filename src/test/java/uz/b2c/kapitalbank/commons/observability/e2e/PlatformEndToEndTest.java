package uz.b2c.kapitalbank.commons.observability.e2e;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Tracer;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * То, что сервис получал от прежней библиотеки и должен получить от новой без правок кода: Prometheus с тегом
 * application, открытые эндпоинты actuator, трассировка Brave с traceId в логах и baggage userGuid в MDC.
 * Отдельная JVM: Spring Boot перенастраивает log4j2 глобально.
 */
class PlatformEndToEndTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class App {
    }

    @Test
    void actuatorPrometheusAndTracingWorkLikeTheOldLibrary() {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String scrape;
        String exposure;
        String tracerType;
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
            .web(WebApplicationType.NONE)
            .properties("spring.application.name=e2e-platform",
                // в тестовом classpath нет spring-messaging, без которого автоконфигурация Kafka не поднимается
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
            .run()) {
            exposure = context.getEnvironment().getProperty("management.endpoints.web.exposure.include");
            scrape = context.getBean(PrometheusMeterRegistry.class).scrape();
            Tracer tracer = context.getBean(Tracer.class);
            tracerType = tracer.getClass().getName();
            Logger log = LoggerFactory.getLogger("e2e");
            Observation.createNotStarted("e2e.work", context.getBean(ObservationRegistry.class)).observe(() -> {
                try (BaggageInScope ignored = tracer.createBaggageInScope("userGuid", "u-42")) {
                    log.info("inside traced work");
                }
            });
        } finally {
            LogManager.shutdown();
            System.setOut(original);
        }
        String out = buffer.toString(StandardCharsets.UTF_8);

        assertThat(exposure).isEqualTo("health,info,metrics,jvm,prometheus");
        assertThat(scrape).contains("application=\"e2e-platform\"");
        assertThat(tracerType).contains("BraveTracer");
        Matcher line = Pattern.compile("\\{[^\\n]*\"message\":\"inside traced work\"[^\\n]*}").matcher(out);
        assertThat(line.find()).as(out).isTrue();
        assertThat(line.group()).containsPattern("\"trace_id\":\"[0-9a-f]{16,32}\"").contains("\"user_guid\":\"u-42\"");
        // без адреса Kafka логи идут только в stdout
        assertThat(out).contains("адрес Kafka не задан");
    }
}
