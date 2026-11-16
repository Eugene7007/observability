package uz.b2c.kapitalbank.commons.observability.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Настройки платформы по умолчанию — те же, что прежняя observability-autoconfiguration приносила своим
 * application.yaml: открытые эндпоинты actuator, общий тег метрик, baggage трассировки, пути Swagger.
 * <p>
 * Источник добавляется с самым низким приоритетом, поэтому любое значение сервис переопределяет в своём
 * application.yml или переменной окружения. Прежний способ (application.yaml внутри jar) срабатывал случайно —
 * только потому, что у сервисов файл называется .yml, — поэтому здесь значения заданы явно.
 * Действуют и при {@code LIB_OBSERVABILITY_ENABLED=false}: это настройки сервиса, а не поведение библиотеки.
 */
public class PlatformDefaults implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE = "libObservabilityPlatformDefaults";

    static final Map<String, Object> DEFAULTS = defaults();

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment, final SpringApplication application) {
        if (!environment.getPropertySources().contains(SOURCE)) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE, DEFAULTS));
        }
    }

    private static Map<String, Object> defaults() {
        Map<String, Object> map = new LinkedHashMap<>();
        // actuator и метрики
        map.put("management.endpoints.web.exposure.include", "${MANAGEMENT_ENDPOINT_IDS:health,info,metrics,jvm,prometheus}");
        map.put("management.metrics.tags.application", "${spring.application.name:unknown}");
        map.put("management.prometheus.metrics.export.enabled", "true");
        // трассировка: userGuid и deviceId передаются между сервисами в baggage и попадают в MDC
        map.put("management.tracing.enabled", "true");
        map.put("management.tracing.baggage.remote-fields", "userGuid,deviceId");
        map.put("management.tracing.baggage.correlation.fields", "userGuid,deviceId");
        // Swagger: выключен, пока не задан ENABLE_SWAGGER=true; пути — как раньше, под /swagger/<сервис>/
        map.put("springdoc.api-docs.enabled", "${ENABLE_SWAGGER:false}");
        map.put("springdoc.api-docs.path", "/swagger/${spring.application.name:service}/v3/api-docs");
        map.put("springdoc.swagger-ui.enabled", "${ENABLE_SWAGGER:false}");
        map.put("springdoc.swagger-ui.tags-sorter", "alpha");
        map.put("springdoc.swagger-ui.path", "/swagger/${spring.application.name:service}/swagger-ui.html");
        map.put("swagger.baseUrl", "${SWAGGER_BASE_URL:https://b2c-apis-stages.kapitalbank.uz/swagger}");
        return Map.copyOf(map);
    }
}
