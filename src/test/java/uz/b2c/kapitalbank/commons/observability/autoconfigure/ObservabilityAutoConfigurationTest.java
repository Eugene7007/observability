package uz.b2c.kapitalbank.commons.observability.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import uz.b2c.kapitalbank.commons.observability.CorrelationInterceptor;
import uz.b2c.kapitalbank.commons.observability.HttpClientLoggingInterceptor;
import uz.b2c.kapitalbank.commons.observability.ObservabilityProperties;
import uz.b2c.kapitalbank.commons.observability.RestTemplateLoggingInstaller;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Переключатели: старое и новое логирование никогда не работают одновременно.
 */
class ObservabilityAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
        .withUserConfiguration(LegacyLogging.class);

    /**
     * Так помечается прежнее логирование сервиса на время перехода.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnLegacyObservability
    static class LegacyLogging {

        @Bean
        String legacyHttpLogging() {
            return "old";
        }
    }

    @Test
    void byDefaultNewLibraryIsOnAndLegacyIsOff() {
        runner.run(context -> {
            assertThat(context).hasBean("libObservabilityCorrelationFilter");
            assertThat(context).hasBean("libObservabilityHttpServerLoggingFilter");
            assertThat(context).hasSingleBean(CorrelationInterceptor.class);
            assertThat(context).hasSingleBean(HttpClientLoggingInterceptor.class);
            assertThat(context).hasSingleBean(RestTemplateLoggingInstaller.class);
            assertThat(context).doesNotHaveBean("legacyHttpLogging");
        });
    }

    @Test
    void masterSwitchOffBringsLegacyBack() {
        runner.withPropertyValues("lib.observability.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean("libObservabilityCorrelationFilter");
            assertThat(context).doesNotHaveBean("libObservabilityHttpServerLoggingFilter");
            assertThat(context).doesNotHaveBean(RestTemplateLoggingInstaller.class);
            assertThat(context).doesNotHaveBean(ObservabilityProperties.class);
            assertThat(context).hasBean("legacyHttpLogging");
        });
    }

    @Test
    void httpLogCanBeSwitchedOffAlone() {
        runner.withPropertyValues("lib.observability.http-log.enabled=false").run(context -> {
            assertThat(context).hasBean("libObservabilityCorrelationFilter");
            assertThat(context).doesNotHaveBean("libObservabilityHttpServerLoggingFilter");
            assertThat(context).doesNotHaveBean(HttpClientLoggingInterceptor.class);
            assertThat(context).hasSingleBean(RestTemplateLoggingInstaller.class);
            assertThat(context).doesNotHaveBean("legacyHttpLogging");
        });
    }

    @Test
    void correlationCanBeSwitchedOffAlone() {
        runner.withPropertyValues("lib.observability.correlation.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean("libObservabilityCorrelationFilter");
            assertThat(context).doesNotHaveBean(CorrelationInterceptor.class);
            assertThat(context).hasBean("libObservabilityHttpServerLoggingFilter");
        });
    }

    @Test
    void settingsBindFromYamlStyleProperties() {
        runner.withPropertyValues(
            "lib.observability.http-log.bodies=always",
            "lib.observability.http-log.body-paths=/internal/v1/hold/**",
            "lib.observability.http-log.max-body-bytes=2048").run(context -> {
                ObservabilityProperties properties = context.getBean(ObservabilityProperties.class);
                assertThat(properties.getHttpLog().getBodies().name()).isEqualTo("ALWAYS");
                assertThat(properties.getHttpLog().getBodyPaths()).containsExactly("/internal/v1/hold/**");
                assertThat(properties.getHttpLog().getMaxBodyBytes()).isEqualTo(2048);
            });
    }

    /**
     * Переменные окружения из манифеста. По правилам Spring Boot дефисы из имени свойства удаляются:
     * http-log.bodies → LIB_OBSERVABILITY_HTTPLOG_BODIES.
     * ConfigurationPropertySources.attach повторяет то, что делает SpringApplication при запуске сервиса.
     */
    @Test
    void environmentVariablesSwitchParts() {
        runner.withInitializer(context -> env(context, Map.of("LIB_OBSERVABILITY_HTTPLOG_ENABLED", "false")))
            .run(context -> assertThat(context)
                .doesNotHaveBean("libObservabilityHttpServerLoggingFilter")
                .hasBean("libObservabilityCorrelationFilter"));
        runner.withInitializer(context -> env(context, Map.of(
                "LIB_OBSERVABILITY_HTTPLOG_BODIES", "ALWAYS",
                "LIB_OBSERVABILITY_HTTPLOG_BODYPATHS", "/internal/v1/hold/**,/internal/v1/p2p/**",
                "LIB_OBSERVABILITY_HTTPLOG_MAXBODYBYTES", "1024")))
            .run(context -> {
                ObservabilityProperties properties = context.getBean(ObservabilityProperties.class);
                assertThat(properties.getHttpLog().getBodies().name()).isEqualTo("ALWAYS");
                assertThat(properties.getHttpLog().getBodyPaths())
                    .containsExactly("/internal/v1/hold/**", "/internal/v1/p2p/**");
                assertThat(properties.getHttpLog().getMaxBodyBytes()).isEqualTo(1024);
            });
        runner.withInitializer(context -> env(context, Map.of("LIB_OBSERVABILITY_ENABLED", "false")))
            .run(context -> assertThat(context).hasBean("legacyHttpLogging")
                .doesNotHaveBean("libObservabilityCorrelationFilter"));
    }

    private static void env(final ConfigurableApplicationContext context, final Map<String, Object> variables) {
        // имя источника важно: по нему Spring Boot понимает, что это переменные окружения, и включает их правила имён
        context.getEnvironment().getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        ConfigurationPropertySources.attach(context.getEnvironment());
    }

    @Test
    void statusLineShowsWhichVariantIsRunning() {
        runner.withPropertyValues("lib.observability.http-log.enabled=false").run(context ->
            assertThat(ObservabilityStatusAutoConfiguration.describe(context.getEnvironment()))
                .startsWith("lib-observability: ВКЛЮЧЕНА").contains("http-log=off").contains("correlation=on"));
        runner.withPropertyValues("lib.observability.enabled=false").run(context ->
            assertThat(ObservabilityStatusAutoConfiguration.describe(context.getEnvironment()))
                .startsWith("lib-observability: ВЫКЛЮЧЕНА"));
    }
}
