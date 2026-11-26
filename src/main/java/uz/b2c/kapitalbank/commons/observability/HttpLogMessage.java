package uz.b2c.kapitalbank.commons.observability;

import java.util.Collections;
import java.util.TreeMap;
import java.util.Map;
import org.apache.logging.log4j.message.MapMessage;

/**
 * Событие HTTP-обмена: короткая строка в {@code message} и отдельные поля по OpenTelemetry semantic conventions.
 * Параллельно хранится прежний набор полей ({@link #legacyFields()}) для Kafka-топика, формат которого
 * сохраняется для старых потребителей до 2.0.
 * <p>
 * Поля читаются JSON-шаблоном лога (резолвер {@code map}), поэтому тела и заголовки уходят отдельными полями,
 * а не склеиваются в многострочный текст, и по ним можно искать. В консоль и в поле {@code message}
 * попадает только краткое описание.
 */
public final class HttpLogMessage extends MapMessage<HttpLogMessage, Object> {

    private static final long serialVersionUID = 1L;

    private final String summary;
    /** Поля в прежнем формате (до rc3) — только для Kafka-топика, который читают старые потребители. */
    private final TreeMap<String, Object> legacy = new TreeMap<>();

    HttpLogMessage(final String summary) {
        super(16);
        this.summary = summary;
    }

    HttpLogMessage field(final String key, final Object value) {
        // значения хранятся как есть: статус, длительность и размеры уходят в JSON числами, а не строками
        if (value != null) {
            with(key, value);
        }
        return this;
    }

    /** Поле прежнего формата для Kafka-топика: в stdout не пишется. */
    HttpLogMessage legacy(final String key, final Object value) {
        if (value != null) {
            legacy.put(key, value);
        }
        return this;
    }

    /** Поля прежнего формата (rc1/rc2) по алфавиту, как их выводил MapMessage, — для layout-legacy-kafka.json. */
    public Map<String, Object> legacyFields() {
        return Collections.unmodifiableMap(legacy);
    }

    public String summary() {
        return summary;
    }

    @Override
    public String getFormattedMessage() {
        return summary;
    }

    @Override
    public void formatTo(final StringBuilder buffer) {
        buffer.append(summary);
    }
}
