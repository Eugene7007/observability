package uz.b2c.kapitalbank.commons.observability;

import org.apache.logging.log4j.message.MapMessage;

/**
 * Событие HTTP-обмена: короткая строка в {@code message} и отдельные поля {@code http.*}.
 * <p>
 * Поля читаются JSON-шаблоном лога (резолвер {@code map}), поэтому тела и заголовки уходят отдельными полями,
 * а не склеиваются в многострочный текст, и по ним можно искать. В консоль и в поле {@code message}
 * попадает только краткое описание.
 */
public final class HttpLogMessage extends MapMessage<HttpLogMessage, Object> {

    private static final long serialVersionUID = 1L;

    private final String summary;

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
