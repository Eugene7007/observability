package uz.b2c.kapitalbank.commons.observability.platform;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Swagger, как в прежней observability-autoconfiguration: адрес сервера и заголовок в описании API,
 * обязательный заголовок {@code Accept-Language} у каждого метода, теги «Контроллер_метод» и CORS для Swagger UI.
 * Подключается, только если в сервисе есть springdoc; собственный бин {@link OpenAPI} сервиса главнее.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({OpenAPI.class, OperationCustomizer.class})
public class SwaggerAutoConfiguration {

    static final String LANGUAGE_HEADER = "Accept-Language";
    static final String LANGUAGE_ENUM = "uz.b2c.kapitalbank.commons.enums.Language";
    static final List<String> FALLBACK_LANGUAGES = List.of("ru-RU", "uz-Latn-UZ", "en-US");
    static final String DEFAULT_LANGUAGE = "ru-RU";
    static final List<String> API_PATHS = List.of("/api/*", "/internal/*", "/admin/*", "/global/*");

    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    OpenAPI libObservabilityOpenApi(@Value("${swagger.baseUrl}") final String baseUrl,
                                    @Value("${spring.application.name:service}") final String service) {
        return new OpenAPI()
            .addServersItem(new Server().url(baseUrl))
            .info(new Info().title(service).description(String.format("%s/%s/v3/api-docs", baseUrl, service)));
    }

    @Bean
    OperationCustomizer libObservabilityLanguageHeader() {
        List<String> languages = languages();
        return (operation, handlerMethod) -> {
            boolean present = operation.getParameters() != null && operation.getParameters().stream()
                .anyMatch(p -> "header".equalsIgnoreCase(p.getIn()) && LANGUAGE_HEADER.equalsIgnoreCase(p.getName()));
            if (!present) {
                StringSchema schema = new StringSchema();
                schema.setEnum(new ArrayList<>(languages));
                schema.setDefault(DEFAULT_LANGUAGE);
                operation.addParametersItem(new Parameter().in("header").name(LANGUAGE_HEADER).required(true).schema(schema));
            }
            operation.setTags(List.of(handlerMethod.getBeanType().getSimpleName() + "_" + operation.getOperationId()));
            return operation;
        };
    }

    /**
     * Заголовки CORS для Swagger UI, когда Swagger включён ({@code ENABLE_SWAGGER=true}). Как и раньше,
     * разрешают запросы с любого источника, поэтому Swagger не должен включаться на проде.
     */
    @Bean
    @ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
    FilterRegistrationBean<OncePerRequestFilter> libObservabilitySwaggerCors() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                            final FilterChain chain) throws ServletException, IOException {
                response.addHeader("Access-Control-Allow-Origin", "*");
                response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                chain.doFilter(request, response);
            }
        });
        registration.setName("libObservabilitySwaggerCors");
        registration.setUrlPatterns(API_PATHS);
        registration.setOrder(5);
        return registration;
    }

    /**
     * Значения заголовка языка берутся из enum Language общих библиотек сервиса, если он есть на classpath,
     * — так список совпадает с тем, что принимают контроллеры; иначе — известные значения.
     */
    static List<String> languages() {
        try {
            Class<?> type = ClassUtils.forName(LANGUAGE_ENUM, SwaggerAutoConfiguration.class.getClassLoader());
            Method getter = type.getMethod("getValue");
            List<String> values = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                values.add(String.valueOf(getter.invoke(constant)));
            }
            return values.isEmpty() ? FALLBACK_LANGUAGES : values;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return FALLBACK_LANGUAGES;
        }
    }
}
