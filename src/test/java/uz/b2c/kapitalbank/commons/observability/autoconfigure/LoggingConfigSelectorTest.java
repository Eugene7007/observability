package uz.b2c.kapitalbank.commons.observability.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Выбор конфигурации log4j2 — то, что переключается переменными окружения в манифесте.
 */
class LoggingConfigSelectorTest {

    private static String select(final MockEnvironment environment) {
        new LoggingConfigSelector(new DeferredLogs()).postProcessEnvironment(environment, new SpringApplication());
        return environment.getProperty("logging.config");
    }

    @Test
    void defaultInVersion1KeepsKafkaDeliveryWhenKafkaIsConfigured() {
        assertThat(select(new MockEnvironment().withProperty("LOGGING_KAFKA_SERVERS", "kafka:9092")))
            .isEqualTo(LoggingConfigSelector.JSON_AND_KAFKA);
        assertThat(select(new MockEnvironment().withProperty("logging.appender.kafka.bootstrap.servers", "kafka:9092")))
            .isEqualTo(LoggingConfigSelector.JSON_AND_KAFKA);
    }

    @Test
    void withoutKafkaAddressLogsGoToStdoutOnly() {
        // локальный запуск, @SpringBootTest в CI: продюсер не должен ходить на localhost:9092
        assertThat(select(new MockEnvironment())).isEqualTo(LoggingConfigSelector.JSON);
    }

    @Test
    void kafkaPropertiesOfOldLibraryAreProvided() {
        MockEnvironment environment = new MockEnvironment().withProperty("LOGGING_KAFKA_TOPIC", "service-logs");
        new LoggingConfigSelector(new DeferredLogs()).postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("logging.appender.kafka.topic")).isEqualTo("service-logs");
        assertThat(environment.getProperty("logging.appender.kafka.security.protocol")).isEqualTo("PLAINTEXT");
    }

    @Test
    void stdoutOnlyWhenVectorCollectsLogs() {
        assertThat(select(new MockEnvironment().withProperty("lib.observability.log.output", "stdout")))
            .isEqualTo(LoggingConfigSelector.JSON);
    }

    @Test
    void kafkaModeForDoubleWriteAndRollback() {
        assertThat(select(new MockEnvironment().withProperty("lib.observability.log.output", "stdout_and_kafka")
            .withProperty("LOGGING_KAFKA_SERVERS", "kafka:9092")))
            .isEqualTo(LoggingConfigSelector.JSON_AND_KAFKA);
    }

    @Test
    void textForLocalRun() {
        assertThat(select(new MockEnvironment().withProperty("lib.observability.log.format", "text")))
            .isEqualTo(LoggingConfigSelector.TEXT);
    }

    @Test
    void libraryConfigWinsOverServiceConfig() {
        // старая библиотека или сам сервис могли задать logging.config — при замене библиотеки сервис не правится
        assertThat(select(new MockEnvironment().withProperty("logging.config", "classpath:my.xml")))
            .isEqualTo(LoggingConfigSelector.JSON);
    }

    @Test
    void serviceConfigStaysWhenLibraryLogsAreSwitchedOff() {
        assertThat(select(new MockEnvironment().withProperty("logging.config", "classpath:my.xml")
            .withProperty("lib.observability.log.enabled", "false")))
            .isEqualTo("classpath:my.xml");
    }

    @Test
    void switchedOffLibraryLeavesLoggingAlone() {
        assertThat(select(new MockEnvironment().withProperty("lib.observability.enabled", "false"))).isNull();
        assertThat(select(new MockEnvironment().withProperty("lib.observability.log.enabled", "false"))).isNull();
    }

    @Test
    void environmentVariablesFromManifestWork() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env", Map.of(
            "LIB_OBSERVABILITY_LOG_OUTPUT", "STDOUT_AND_KAFKA", "LOGGING_KAFKA_SERVERS", "kafka:9092")));
        new LoggingConfigSelector(new DeferredLogs()).postProcessEnvironment(environment, new SpringApplication());
        assertThat(environment.getProperty("logging.config")).isEqualTo(LoggingConfigSelector.JSON_AND_KAFKA);

        StandardEnvironment off = new StandardEnvironment();
        off.getPropertySources().addFirst(new SystemEnvironmentPropertySource("env", Map.of(
            "LIB_OBSERVABILITY_ENABLED", "false")));
        new LoggingConfigSelector(new DeferredLogs()).postProcessEnvironment(off, new SpringApplication());
        assertThat(off.getProperty("logging.config")).isNull();
    }
}
