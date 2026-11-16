package uz.b2c.kapitalbank.commons.observability.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import uz.b2c.kapitalbank.commons.observability.ObservabilityProperties;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Включает помеченную конфигурацию сервиса только когда библиотека выключена
 * ({@code lib.observability.enabled=false}). Этим помечается прежнее логирование сервиса на время перехода:
 * одна переменная окружения переключает сервис между старым и новым вариантом, и они никогда не работают вместе.
 * Когда переход закончен, помеченный код удаляется вместе с аннотацией.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = ObservabilityProperties.PREFIX + ".enabled", havingValue = "false")
public @interface ConditionalOnLegacyObservability {
}
