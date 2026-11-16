package uz.b2c.kapitalbank.commons.observability;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Добавляет интерцепторы корреляции и логирования во все RestTemplate: и в бины (в том числе созданные
 * через {@code new}), и в шаблоны, собранные через RestTemplateBuilder вне контекста. Повторно не добавляет.
 * Порядок задают сами интерцепторы через Ordered: сначала заголовки корреляции, последним — логирование.
 * Любой из интерцепторов может отсутствовать, если его часть библиотеки выключена.
 */
public final class RestTemplateLoggingInstaller implements RestTemplateCustomizer, BeanPostProcessor {

    private final Supplier<CorrelationInterceptor> correlation;
    private final Supplier<HttpClientLoggingInterceptor> logging;

    public RestTemplateLoggingInstaller(final CorrelationInterceptor correlation,
                                        final HttpClientLoggingInterceptor logging) {
        this(() -> correlation, () -> logging);
    }

    /** Интерцепторы берутся лениво: пост-процессор создаётся раньше остальных бинов. */
    public RestTemplateLoggingInstaller(final Supplier<CorrelationInterceptor> correlation,
                                        final Supplier<HttpClientLoggingInterceptor> logging) {
        this.correlation = correlation;
        this.logging = logging;
    }

    /**
     * Все бины RestTemplate сервиса, в том числе созданные через {@code new RestTemplate()}: так же,
     * как это делала прежняя библиотека, поэтому замена библиотеки не требует правок кода.
     */
    @Override
    public Object postProcessAfterInitialization(final Object bean, final String beanName) {
        if (bean instanceof RestTemplate restTemplate) {
            customize(restTemplate);
        }
        return bean;
    }

    @Override
    public void customize(final RestTemplate restTemplate) {
        CorrelationInterceptor correlation = this.correlation.get();
        HttpClientLoggingInterceptor logging = this.logging.get();
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(restTemplate.getInterceptors());
        if (correlation != null && interceptors.stream().noneMatch(CorrelationInterceptor.class::isInstance)) {
            interceptors.add(correlation);
        }
        if (logging != null && interceptors.stream().noneMatch(HttpClientLoggingInterceptor.class::isInstance)) {
            interceptors.add(logging);
        }
        restTemplate.setInterceptors(interceptors);
    }
}
