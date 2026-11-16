package uz.b2c.kapitalbank.commons.observability.autoconfigure;

import org.apache.logging.log4j.LogManager;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import uz.b2c.kapitalbank.commons.observability.ObservabilityProperties;

/**
 * Одна строка в лог при старте: какие части библиотеки включены. Работает и при выключенной библиотеке,
 * чтобы после переключения переменной было видно, какой вариант реально запустился.
 */
@AutoConfiguration
public class ObservabilityStatusAutoConfiguration {

    @Bean
    SmartInitializingSingleton libObservabilityStatusReporter(final Environment environment) {
        return () -> LogManager.getLogger("lib.observability").info(describe(environment));
    }

    static String describe(final Environment environment) {
        String p = ObservabilityProperties.PREFIX;
        boolean enabled = environment.getProperty(p + ".enabled", Boolean.class, true);
        String result;
        if (enabled) {
            result = "lib-observability: ВКЛЮЧЕНА"
                + "; correlation=" + onOff(environment.getProperty(p + ".correlation.enabled", Boolean.class, true))
                + "; http-log=" + onOff(environment.getProperty(p + ".http-log.enabled", Boolean.class, true))
                + "; bodies=" + Binder.get(environment).bind(p + ".http-log.bodies", String.class).orElse("ON_ERROR")
                + "; logging.config=" + environment.getProperty("logging.config", "по умолчанию сервиса");
        } else {
            result = "lib-observability: ВЫКЛЮЧЕНА, работает прежнее логирование сервиса"
                + " (конфигурации с @ConditionalOnLegacyObservability)";
        }
        return result;
    }

    private static String onOff(final boolean value) {
        return value ? "on" : "off";
    }
}
