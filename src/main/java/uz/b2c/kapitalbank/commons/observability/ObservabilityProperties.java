package uz.b2c.kapitalbank.commons.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Все переключатели библиотеки. Каждый задаётся и в application.yml, и переменной окружения
 * (например, {@code LIB_OBSERVABILITY_ENABLED=false}), поэтому включение и откат делаются правкой манифеста
 * и перезапуском пода, без новой сборки.
 */
@ConfigurationProperties(prefix = ObservabilityProperties.PREFIX)
public class ObservabilityProperties {

    public static final String PREFIX = "lib.observability";

    /**
     * Главный выключатель. false — библиотека не делает ничего: нет фильтров, интерцепторов и своей конфигурации
     * логирования, а код сервиса, помеченный {@code @ConditionalOnLegacyObservability}, включается обратно.
     */
    private boolean enabled = true;

    private final Log log = new Log();

    private final Correlation correlation = new Correlation();

    private final HttpLogProperties httpLog = new HttpLogProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public Log getLog() {
        return log;
    }

    public Correlation getCorrelation() {
        return correlation;
    }

    public HttpLogProperties getHttpLog() {
        return httpLog;
    }

    /**
     * Куда и в каком виде пишутся логи. Читается до запуска логирования, см. {@code LoggingConfigSelector}.
     */
    public static class Log {

        /**
         * Брать ли конфигурацию log4j2 из библиотеки. false — остаётся конфигурация сервиса
         * (его log4j2-spring.xml или logging.config), остальная библиотека продолжает работать.
         */
        private boolean enabled = true;

        /**
         * Куда уходят логи.
         */
        private Output output = Output.STDOUT_AND_KAFKA;

        /**
         * Формат вывода в консоль. TEXT — для локального запуска, в кластере всегда JSON.
         */
        private Format format = Format.JSON;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

        public Output getOutput() {
            return output;
        }

        public void setOutput(final Output output) {
            this.output = output;
        }

        public Format getFormat() {
            return format;
        }

        public void setFormat(final Format format) {
            this.format = format;
        }
    }

    public enum Output {
        /** Целевой режим: JSON в stdout, доставку делает агент в кластере (Vector). Умолчание с версии 2.0. */
        STDOUT,
        /**
         * Переходный режим и умолчание версий 1.x: stdout как в STDOUT плюс прежний Kafka-топик в прежнем формате,
         * через ограниченную асинхронную очередь. Пока Vector не собирает stdout, это единственный путь логов
         * в привычный поиск. Адрес и топик — из переменных LOGGING_KAFKA_*, которые уже есть в окружении сервисов;
         * kafka-clients библиотека 1.x приносит сама.
         */
        STDOUT_AND_KAFKA
    }

    public enum Format {
        JSON,
        TEXT
    }

    public static class Correlation {

        /**
         * correlationId, clientDeviceId, userGuid и deviceId: из входящих заголовков в MDC и в исходящие вызовы.
         */
        private boolean enabled = true;

        /**
         * Хосты, которым передаются UserGuid и DeviceId (шаблоны со «*», например {@code *.svc.cluster.local}).
         * Пусто — всем, как прежняя библиотека: заголовок UserGuid читают контроллеры соседних сервисов,
         * и ограничение без списка внутренних хостов сломало бы вызовы. correlationId передаётся всегда.
         */
        private List<String> userContextHosts = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getUserContextHosts() {
            return userContextHosts;
        }

        public void setUserContextHosts(final List<String> userContextHosts) {
            this.userContextHosts = userContextHosts;
        }
    }
}
