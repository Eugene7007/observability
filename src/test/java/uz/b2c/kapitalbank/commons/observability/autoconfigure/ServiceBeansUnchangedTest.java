package uz.b2c.kapitalbank.commons.observability.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Защитный тест: библиотека не меняет то, как сервис сериализует и разбирает данные. Конвертеры MVC, ObjectMapper,
 * WebMvcConfigurer и конвертеры {@code new RestTemplate()} одинаковы с библиотекой и без неё. Так ловится, например,
 * транзитивная jackson-dataformat-xml, которая поставила бы XML-конвертер раньше JSON во всех сервисах.
 */
class ServiceBeansUnchangedTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, WebMvcAutoConfiguration.class,
            ObservabilityAutoConfiguration.class));

    @Test
    void serializationBeansAreTheSameWithAndWithoutLibrary() {
        AtomicReference<Map<String, Object>> without = new AtomicReference<>();
        AtomicReference<Map<String, Object>> with = new AtomicReference<>();
        runner.withPropertyValues("lib.observability.enabled=false").run(context -> without.set(snapshot(context)));
        runner.run(context -> with.set(snapshot(context)));

        assertThat(with.get()).isEqualTo(without.get());
    }

    @Test
    void restTemplateHasNoXmlConverterOnLibraryClasspath() {
        assertThat(new RestTemplate().getMessageConverters())
            .noneMatch(converter -> converter.getClass().getName().contains("Xml"));
    }

    private static Map<String, Object> snapshot(final ApplicationContext context) {
        Map<String, Object> result = new TreeMap<>();
        result.put("objectMappers", List.copyOf(new TreeMap<>(context.getBeansOfType(ObjectMapper.class)).keySet()));
        result.put("webMvcConfigurers",
            List.copyOf(new TreeMap<>(context.getBeansOfType(WebMvcConfigurer.class)).keySet()));
        result.put("httpMessageConverters", context.getBean(HttpMessageConverters.class).getConverters().stream()
            .map(converter -> converter.getClass().getName()).toList());
        result.put("mvcConverters", context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters().stream()
            .map(converter -> converter.getClass().getName()).toList());
        return result;
    }
}
