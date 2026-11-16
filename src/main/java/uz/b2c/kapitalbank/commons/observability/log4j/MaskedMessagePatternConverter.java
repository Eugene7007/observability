package uz.b2c.kapitalbank.commons.observability.log4j;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;
import uz.b2c.kapitalbank.commons.observability.LogTextMasker;

/**
 * {@code %maskedMsg} для текстового формата (локальный запуск): сообщение после маскирования ПДн.
 */
@Plugin(name = "MaskedMessagePatternConverter", category = PatternConverter.CATEGORY)
@ConverterKeys({"maskedMsg"})
public final class MaskedMessagePatternConverter extends LogEventPatternConverter {

    private static final MaskedMessagePatternConverter INSTANCE = new MaskedMessagePatternConverter();

    private MaskedMessagePatternConverter() {
        super("maskedMsg", "message");
    }

    public static MaskedMessagePatternConverter newInstance(final String[] options) {
        return INSTANCE;
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        toAppendTo.append(LogTextMasker.maskOrPlaceholder(event.getMessage().getFormattedMessage()));
    }
}
