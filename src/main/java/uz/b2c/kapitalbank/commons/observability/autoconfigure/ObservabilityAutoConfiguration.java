package uz.b2c.kapitalbank.commons.observability.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.client.RestTemplate;
import uz.b2c.kapitalbank.commons.observability.kafka.KafkaCorrelationPostProcessor;
import uz.b2c.kapitalbank.commons.observability.CorrelationFilter;
import uz.b2c.kapitalbank.commons.observability.CorrelationInterceptor;
import uz.b2c.kapitalbank.commons.observability.HttpClientLoggingInterceptor;
import uz.b2c.kapitalbank.commons.observability.HttpLogSettings;
import uz.b2c.kapitalbank.commons.observability.HttpServerLoggingFilter;
import uz.b2c.kapitalbank.commons.observability.MaskingRules;
import uz.b2c.kapitalbank.commons.observability.ObservabilityProperties;
import uz.b2c.kapitalbank.commons.observability.RestTemplateLoggingInstaller;

/**
 * Корреляция запросов и логирование HTTP-обменов. Каждая часть выключается своим свойством,
 * вся библиотека — {@code lib.observability.enabled=false}.
 * <p>
 * Имена бинов начинаются с {@code libObservability}, чтобы не столкнуться с собственными бинами сервиса,
 * которые на время перехода остаются в коде под {@link ConditionalOnLegacyObservability}.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ObservabilityProperties.PREFIX + ".enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    /**
     * Корреляция раньше логирования, чтобы correlationId уже был в MDC, когда пишется запись о запросе.
     * Оба фильтра стоят в самом начале цепочки — до фильтров безопасности и прочих фильтров сервиса,
     * чтобы в лог попадали и запросы, отклонённые ими (401/403, ошибки в фильтрах).
     */
    static final int CORRELATION_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
    static final int LOGGING_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    /**
     * Правила маскирования для строк лога: базовые правила банка плюс поля команды. До этого момента
     * (пока поднимается контекст) строки лога маскируются только базовыми правилами.
     */
    @Bean
    MaskingRules libObservabilityMaskingRules(final ObservabilityProperties properties) {
        MaskingRules rules = MaskingRules.of(properties.getHttpLog().getMasking());
        MaskingRules.install(rules);
        return rules;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = ObservabilityProperties.PREFIX + ".correlation.enabled", havingValue = "true", matchIfMissing = true)
    static class CorrelationConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "libObservabilityCorrelationFilter")
        FilterRegistrationBean<CorrelationFilter> libObservabilityCorrelationFilter() {
            FilterRegistrationBean<CorrelationFilter> registration = new FilterRegistrationBean<>(new CorrelationFilter());
            registration.setName("libObservabilityCorrelationFilter");
            registration.setOrder(CORRELATION_FILTER_ORDER);
            registration.addUrlPatterns("/*");
            return registration;
        }

        @Bean
        @ConditionalOnMissingBean
        CorrelationInterceptor libObservabilityCorrelationInterceptor(final ObservabilityProperties properties) {
            return new CorrelationInterceptor(properties.getCorrelation().getUserContextHosts());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(name = ObservabilityProperties.PREFIX + ".http-log.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnClass(name = "com.fasterxml.jackson.core.JsonFactory")
    static class HttpLogConfiguration {

        @Bean
        @ConditionalOnMissingBean
        HttpLogSettings libObservabilityHttpLogSettings(final ObservabilityProperties properties) {
            return new HttpLogSettings(properties.getHttpLog());
        }

        @Bean
        @ConditionalOnMissingBean(name = "libObservabilityHttpServerLoggingFilter")
        FilterRegistrationBean<HttpServerLoggingFilter> libObservabilityHttpServerLoggingFilter(
            final HttpLogSettings settings) {
            FilterRegistrationBean<HttpServerLoggingFilter> registration =
                new FilterRegistrationBean<>(new HttpServerLoggingFilter(settings));
            registration.setName("libObservabilityHttpServerLoggingFilter");
            registration.setOrder(LOGGING_FILTER_ORDER);
            registration.addUrlPatterns("/*");
            // ERROR — чтобы в запись об ошибке попало тело страницы ошибки (sendError, исключение)
            registration.setDispatcherTypes(jakarta.servlet.DispatcherType.REQUEST, jakarta.servlet.DispatcherType.ASYNC,
                jakarta.servlet.DispatcherType.ERROR);
            return registration;
        }

        /** Пишет отложенную запись об ошибке, если прохода ERROR не было. */
        @Bean
        @ConditionalOnMissingBean(name = "libObservabilityHttpLogCompletion")
        org.springframework.boot.web.servlet.ServletListenerRegistrationBean<jakarta.servlet.ServletRequestListener>
            libObservabilityHttpLogCompletion() {
            return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(
                new jakarta.servlet.ServletRequestListener() {
                    @Override
                    public void requestDestroyed(final jakarta.servlet.ServletRequestEvent event) {
                        HttpServerLoggingFilter.completeRequest(event.getServletRequest());
                    }
                });
        }

        @Bean
        @ConditionalOnMissingBean
        HttpClientLoggingInterceptor libObservabilityHttpClientLoggingInterceptor(final HttpLogSettings settings) {
            return new HttpClientLoggingInterceptor(settings);
        }
    }

    /**
     * Подключение интерцепторов ко всем RestTemplate сервиса: к бинам (как это делала прежняя библиотека)
     * и к шаблонам, собранным через RestTemplateBuilder. Пост-процессор статический и берёт интерцепторы
     * лениво, чтобы не тянуть зависимости раньше времени.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestTemplate.class)
    static class RestTemplateConfiguration {

        @Bean
        static RestTemplateLoggingInstaller libObservabilityRestTemplateInstaller(
            final ObjectProvider<CorrelationInterceptor> correlation,
            final ObjectProvider<HttpClientLoggingInterceptor> logging) {
            return new RestTemplateLoggingInstaller(correlation::getIfAvailable, logging::getIfAvailable);
        }
    }

    /**
     * Корреляция через Kafka: correlationId и clientDeviceId в заголовках сообщений и в MDC консьюмера.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    @ConditionalOnProperty(name = ObservabilityProperties.PREFIX + ".correlation.enabled", havingValue = "true", matchIfMissing = true)
    static class KafkaCorrelationConfiguration {

        @Bean
        static KafkaCorrelationPostProcessor libObservabilityKafkaCorrelation() {
            return new KafkaCorrelationPostProcessor();
        }
    }
}
