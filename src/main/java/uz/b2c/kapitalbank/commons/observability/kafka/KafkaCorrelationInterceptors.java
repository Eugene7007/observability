package uz.b2c.kapitalbank.commons.observability.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;
import uz.b2c.kapitalbank.commons.observability.Correlation;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Корреляция через Kafka: продюсер кладёт correlationId и clientDeviceId из MDC в заголовки сообщения,
 * консьюмер достаёт их в MDC на время обработки записи. Имена заголовков те же, что у прежней
 * observability-autoconfiguration, поэтому сервисы на старой и новой библиотеке понимают друг друга.
 * В отличие от прежней реализации MDC очищается после каждой записи, чтобы идентификатор
 * одного сообщения не попадал в логи следующего.
 */
public final class KafkaCorrelationInterceptors {

    private KafkaCorrelationInterceptors() {
    }

    /** Для {@code ConcurrentKafkaListenerContainerFactory}. */
    public static final class Consuming<K, V> implements RecordInterceptor<K, V> {

        @Override
        public ConsumerRecord<K, V> intercept(final ConsumerRecord<K, V> record, final Consumer<K, V> consumer) {
            String correlationId = Correlation.sanitizeOrNew(header(record.headers(), Correlation.CORRELATION_ID));
            String clientDeviceId = Correlation.sanitize(header(record.headers(), Correlation.CLIENT_DEVICE_ID));
            MDC.put(Correlation.CORRELATION_ID, correlationId);
            MDC.put(Correlation.CLIENT_DEVICE_ID, clientDeviceId != null ? clientDeviceId : correlationId);
            return record;
        }

        @Override
        public void afterRecord(final ConsumerRecord<K, V> record, final Consumer<K, V> consumer) {
            MDC.remove(Correlation.CORRELATION_ID);
            MDC.remove(Correlation.CLIENT_DEVICE_ID);
        }

        private static String header(final Headers headers, final String name) {
            Header header = headers.lastHeader(name);
            return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        }
    }

    /** Для {@code KafkaTemplate}. */
    public static final class Producing<K, V> implements ProducerInterceptor<K, V> {

        @Override
        public ProducerRecord<K, V> onSend(final ProducerRecord<K, V> record) {
            String correlationId = MDC.get(Correlation.CORRELATION_ID);
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = Correlation.newId();
            }
            String clientDeviceId = MDC.get(Correlation.CLIENT_DEVICE_ID);
            set(record.headers(), Correlation.CORRELATION_ID, correlationId);
            set(record.headers(), Correlation.CLIENT_DEVICE_ID,
                clientDeviceId == null || clientDeviceId.isBlank() ? correlationId : clientDeviceId);
            return record;
        }

        private static void set(final Headers headers, final String name, final String value) {
            headers.remove(name);
            headers.add(name, value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void onAcknowledgement(final RecordMetadata metadata, final Exception exception) {
            // не нужно
        }

        @Override
        public void close() {
            // не нужно
        }

        @Override
        public void configure(final Map<String, ?> configs) {
            // не нужно
        }
    }
}
