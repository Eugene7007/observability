package uz.b2c.kapitalbank.commons.observability.kafka;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CompositeRecordInterceptor;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.CompositeProducerInterceptor;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

/**
 * Подключает корреляцию ко всем фабрикам слушателей и KafkaTemplate сервиса — как прежняя библиотека,
 * без изменений кода сервиса. В отличие от прежней, не затирает интерцептор, который сервис уже задал
 * (сам или через бин {@code RecordInterceptor}), а объединяет с ним: сначала корреляция, потом интерцептор сервиса.
 */
public final class KafkaCorrelationPostProcessor implements BeanPostProcessor {

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object postProcessAfterInitialization(final Object bean, final String beanName) {
        if (bean instanceof AbstractKafkaListenerContainerFactory factory) {
            Object existing = read(AbstractKafkaListenerContainerFactory.class, "recordInterceptor", factory);
            RecordInterceptor correlation = new KafkaCorrelationInterceptors.Consuming<>();
            if (existing == null) {
                factory.setRecordInterceptor(correlation);
            } else if (!(existing instanceof KafkaCorrelationInterceptors.Consuming) && !containsCorrelation(existing)) {
                factory.setRecordInterceptor(new CompositeRecordInterceptor(correlation, (RecordInterceptor) existing));
            }
        } else if (bean instanceof KafkaTemplate template) {
            Object existing = read(KafkaTemplate.class, "producerInterceptor", template);
            ProducerInterceptor correlation = new KafkaCorrelationInterceptors.Producing<>();
            if (existing == null) {
                template.setProducerInterceptor(correlation);
            } else if (!(existing instanceof KafkaCorrelationInterceptors.Producing)) {
                template.setProducerInterceptor(new CompositeProducerInterceptor(correlation, (ProducerInterceptor) existing));
            }
        }
        return bean;
    }

    private static boolean containsCorrelation(final Object composite) {
        if (!(composite instanceof CompositeRecordInterceptor<?, ?>)) {
            return false;
        }
        Object delegates = read(CompositeRecordInterceptor.class, "delegates", composite);
        return delegates instanceof Iterable<?> list && java.util.stream.StreamSupport.stream(list.spliterator(), false)
            .anyMatch(KafkaCorrelationInterceptors.Consuming.class::isInstance);
    }

    /** Публичного геттера у spring-kafka 3.0 нет, поэтому текущее значение читается из поля. */
    private static Object read(final Class<?> type, final String name, final Object target) {
        Field field = ReflectionUtils.findField(type, name);
        if (field == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(field);
        return ReflectionUtils.getField(field, target);
    }
}
