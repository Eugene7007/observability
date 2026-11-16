package uz.b2c.kapitalbank.commons.observability.platform;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withPropertyValues("spring.application.name=svc", "swagger.baseUrl=https://apis/swagger")
        .withConfiguration(AutoConfigurations.of(SwaggerAutoConfiguration.class));

    @Test
    void defaultsMatchTheOldLibraryAndCanBeOverridden() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", "svc")
            .withProperty("management.endpoints.web.exposure.include", "health");
        new PlatformDefaults().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(environment.getProperty("management.metrics.tags.application")).isEqualTo("svc");
        assertThat(environment.getProperty("management.tracing.baggage.remote-fields")).isEqualTo("userGuid,deviceId");
        assertThat(environment.getProperty("springdoc.api-docs.path")).isEqualTo("/swagger/svc/v3/api-docs");
        assertThat(environment.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
    }

    @Test
    void openApiAndLanguageHeaderLikeBefore() throws Exception {
        runner.run(context -> {
            OpenAPI api = context.getBean(OpenAPI.class);
            assertThat(api.getServers().get(0).getUrl()).isEqualTo("https://apis/swagger");
            assertThat(api.getInfo().getTitle()).isEqualTo("svc");

            OperationCustomizer customizer = context.getBean("libObservabilityLanguageHeader", OperationCustomizer.class);
            Operation operation = customizer.customize(new Operation().operationId("get"),
                new HandlerMethod(new Sample(), Sample.class.getMethod("get")));
            assertThat(operation.getParameters()).singleElement().satisfies(p -> {
                assertThat(p.getName()).isEqualTo("Accept-Language");
                assertThat(p.getRequired()).isTrue();
                assertThat(p.getSchema().getEnum()).contains("ru-RU");
            });
            assertThat(operation.getTags()).containsExactly("Sample_get");
            assertThat(context).doesNotHaveBean("libObservabilitySwaggerCors");
        });
    }

    @Test
    void serviceOwnOpenApiWinsAndCorsOnlyWithSwagger() {
        runner.withPropertyValues("springdoc.api-docs.enabled=true")
            .withBean("serviceOpenApi", OpenAPI.class, () -> new OpenAPI().info(new io.swagger.v3.oas.models.info.Info().title("own")))
            .run(context -> {
                assertThat(context.getBean(OpenAPI.class).getInfo().getTitle()).isEqualTo("own");
                assertThat(context).hasBean("libObservabilitySwaggerCors");
            });
    }

    static class Sample {
        public String get() {
            return "ok";
        }
    }
}
