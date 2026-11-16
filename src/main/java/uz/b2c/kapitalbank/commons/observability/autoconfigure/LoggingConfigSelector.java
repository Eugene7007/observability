package uz.b2c.kapitalbank.commons.observability.autoconfigure;

import org.apache.commons.logging.Log;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;
import uz.b2c.kapitalbank.commons.observability.ObservabilityProperties;

import java.util.Map;

/**
 * Выбирает конфигурацию log4j2 из библиотеки по {@code lib.observability.log.*}.
 * <p>
 * Логирование в Spring Boot настраивается раньше, чем создаются бины, поэтому выбор сделан на этапе подготовки
 * окружения: здесь уже прочитаны application.yml и переменные окружения, но логирование ещё не запущено.
 * Значение {@code logging.config} библиотеки главнее заданного сервисом: при замене старой библиотеки
 * новой сервис не правится. Конфигурация сервиса остаётся только при выключенной библиотеке или
 * {@code lib.observability.log.enabled=false}.
 */
public class LoggingConfigSelector implements EnvironmentPostProcessor, Ordered {

    static final String LOGGING_CONFIG = "logging.config";
    static final String PROPERTY_SOURCE = "libObservabilityLogging";
    static final String JSON = "classpath:lib-observability/log4j2-json.xml";
    static final String JSON_AND_KAFKA = "classpath:lib-observability/log4j2-json-kafka.xml";
    static final String TEXT = "classpath:lib-observability/log4j2-text.xml";
    static final String KAFKA_DEFAULTS = "libObservabilityKafkaDefaults";
    static final String KAFKA_SERVERS = "logging.appender.kafka.bootstrap.servers";
    static final String LOGBACK = "ch.qos.logback.classic.LoggerContext";
    static final java.util.Set<String> SASL_MECHANISMS = java.util.Set.of(
        "PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512", "GSSAPI", "OAUTHBEARER");

    /**
     * Настройки Kafka-аппендера — те же свойства и переменные окружения, что у прежней библиотеки
     * (log-autoconfiguration), с теми же значениями по умолчанию. Сервис может переопределить их в application.yml.
     */
    static final Map<String, Object> KAFKA_PROPERTIES = Map.of(
        KAFKA_SERVERS, "${LOGGING_KAFKA_SERVERS:localhost:9092}",
        "logging.appender.kafka.security.protocol", "${LOGGING_KAFKA_PROTOCOL:PLAINTEXT}",
        "logging.appender.kafka.topic", "${LOGGING_KAFKA_TOPIC:errors}",
        "logging.appender.kafka.username", "${LOGGING_KAFKA_USERNAME:username}",
        "logging.appender.kafka.password", "${LOGGING_KAFKA_PASSWORD:password}",
        "logging.appender.kafka.sasl.mechanism", "${LOGGING_KAFKA_SASL_MECHANISM:SASL_PLAINTEXT}");

    private final Log log;

    public LoggingConfigSelector(final DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(LoggingConfigSelector.class);
    }

    @Override
    public int getOrder() {
        // после чтения application.yml и config data
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment, final SpringApplication application) {
        Binder binder = Binder.get(environment);
        String prefix = ObservabilityProperties.PREFIX;
        boolean enabled = binder.bind(prefix + ".enabled", Boolean.class).orElse(true);
        boolean logEnabled = binder.bind(prefix + ".log.enabled", Boolean.class).orElse(true);
        if (!enabled || !logEnabled) {
            log.warn("lib-observability: конфигурация логирования не меняется (библиотека или её логи выключены);"
                + " маскирование ПДн в строках лога в этом режиме не работает");
            return;
        }
        if (isPresent(LOGBACK)) {
            // Spring Boot выбирает Logback, если он на classpath, и упал бы на XML для log4j2
            log.error("lib-observability: в сервисе остался Logback (spring-boot-starter-logging не исключён),"
                + " конфигурация логов библиотеки не применяется. Исключите spring-boot-starter-logging в build.gradle");
            return;
        }
        // Библиотека отвечает за формат и маскирование логов, поэтому её конфигурация главнее logging.config
        // сервиса: при замене старой библиотеки новой ничего в сервисе править не нужно. Вернуть свою
        // конфигурацию сервис может только явно: LIB_OBSERVABILITY_LOG_ENABLED=false.
        String previous = environment.getProperty(LOGGING_CONFIG);
        ObservabilityProperties.Output output = binder.bind(prefix + ".log.output", ObservabilityProperties.Output.class)
            .orElse(ObservabilityProperties.Output.STDOUT_AND_KAFKA);
        ObservabilityProperties.Format format = binder.bind(prefix + ".log.format", ObservabilityProperties.Format.class)
            .orElse(ObservabilityProperties.Format.JSON);
        if (output == ObservabilityProperties.Output.STDOUT_AND_KAFKA && !kafkaConfigured(environment)) {
            // без адреса Kafka (локальный запуск, тесты, новый сервис) продюсер ходил бы на localhost:9092
            output = ObservabilityProperties.Output.STDOUT;
            log.warn("lib-observability: адрес Kafka не задан (LOGGING_KAFKA_SERVERS), логи пишутся только в stdout");
        }
        environment.getPropertySources().addLast(new MapPropertySource(KAFKA_DEFAULTS, KAFKA_PROPERTIES));
        if (output == ObservabilityProperties.Output.STDOUT_AND_KAFKA) {
            warnOnSaslMismatch(environment);
        }
        String location = select(output, format);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, Map.of(LOGGING_CONFIG, location)));
        String message = "lib-observability: логирование " + format + " → " + output + " (" + location + ")";
        if (previous == null || previous.equals(location)) {
            log.info(message);
        } else {
            log.warn(message + "; logging.config сервиса (" + previous + ") не используется — его аппендеры"
                + " (файлы, другие топики) отключены. Вернуть его: LIB_OBSERVABILITY_LOG_ENABLED=false");
        }
    }

    /**
     * Значение sasl.mechanism по умолчанию у старой библиотеки — «SASL_PLAINTEXT», это протокол, а не механизм.
     * При протоколе PLAINTEXT Kafka его игнорирует, а при SASL_* аппендер не стартует, и логи в топик молча
     * не идут. Значение оставлено для совместимости, но о несовпадении предупреждаем при старте.
     */
    private void warnOnSaslMismatch(final ConfigurableEnvironment environment) {
        String protocol = environment.getProperty("logging.appender.kafka.security.protocol", "PLAINTEXT");
        String mechanism = environment.getProperty("logging.appender.kafka.sasl.mechanism", "");
        if (protocol.toUpperCase(java.util.Locale.ROOT).startsWith("SASL") && !SASL_MECHANISMS.contains(mechanism)) {
            log.warn("lib-observability: LOGGING_KAFKA_PROTOCOL=" + protocol + ", но LOGGING_KAFKA_SASL_MECHANISM="
                + mechanism + " не является механизмом SASL (" + SASL_MECHANISMS + "): логи в Kafka отправляться не будут");
        }
    }

    static boolean kafkaConfigured(final ConfigurableEnvironment environment) {
        return environment.containsProperty("LOGGING_KAFKA_SERVERS") || environment.containsProperty(KAFKA_SERVERS);
    }

    static boolean isPresent(final String className) {
        return ClassUtils.isPresent(className, LoggingConfigSelector.class.getClassLoader());
    }

    static String select(final ObservabilityProperties.Output output, final ObservabilityProperties.Format format) {
        String location;
        if (format == ObservabilityProperties.Format.TEXT) {
            location = TEXT;
        } else if (output == ObservabilityProperties.Output.STDOUT_AND_KAFKA) {
            location = JSON_AND_KAFKA;
        } else {
            location = JSON;
        }
        return location;
    }
}
