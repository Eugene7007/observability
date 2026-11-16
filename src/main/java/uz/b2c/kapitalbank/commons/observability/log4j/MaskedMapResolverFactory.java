package uz.b2c.kapitalbank.commons.observability.log4j;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.layout.template.json.resolver.EventResolver;
import org.apache.logging.log4j.layout.template.json.resolver.EventResolverContext;
import org.apache.logging.log4j.layout.template.json.resolver.EventResolverFactory;
import org.apache.logging.log4j.layout.template.json.resolver.TemplateResolverConfig;
import org.apache.logging.log4j.layout.template.json.resolver.TemplateResolverFactory;
import org.apache.logging.log4j.layout.template.json.util.JsonWriter;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.util.IndexedReadOnlyStringMap;
import uz.b2c.kapitalbank.commons.observability.HttpLogMessage;
import uz.b2c.kapitalbank.commons.observability.LogTextMasker;
import uz.b2c.kapitalbank.commons.observability.MaskingRules;

/**
 * Резолвер {@code "$resolver": "maskedMap"}: поля {@link MapMessage} как отдельные поля записи.
 * Поля записей HTTP-логирования ({@link HttpLogMessage}) пишутся как есть — они уже замаскированы при захвате.
 * Поля любого другого MapMessage (например, {@code log.info(new MapMessage<>().with("phone", …))}) маскируются
 * по имени поля и по тексту значения и получают префикс {@code prefix} (по умолчанию {@code data.}),
 * чтобы не перезаписать основные поля записи вроде {@code message}.
 */
@Plugin(name = "MaskedMapResolverFactory", category = TemplateResolverFactory.CATEGORY)
public final class MaskedMapResolverFactory implements EventResolverFactory {

    private static final MaskedMapResolverFactory INSTANCE = new MaskedMapResolverFactory();

    private MaskedMapResolverFactory() {
    }

    @PluginFactory
    public static MaskedMapResolverFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public String getName() {
        return "maskedMap";
    }

    @Override
    public EventResolver create(final EventResolverContext context, final TemplateResolverConfig config) {
        String prefix = config.exists("prefix") ? config.getString("prefix") : "data.";
        return new Resolver(prefix);
    }

    private static final class Resolver implements EventResolver {

        private final String prefix;

        Resolver(final String prefix) {
            this.prefix = prefix;
        }

        @Override
        public boolean isFlattening() {
            return true;
        }

        @Override
        public boolean isResolvable(final LogEvent event) {
            Message message = event.getMessage();
            return message instanceof MapMessage<?, ?> map && !map.getIndexedReadOnlyStringMap().isEmpty();
        }

        @Override
        public void resolve(final LogEvent event, final JsonWriter writer) {
            resolve(event, writer, false);
        }

        @Override
        public void resolve(final LogEvent event, final JsonWriter writer, final boolean succeedingEntry) {
            if (!(event.getMessage() instanceof MapMessage<?, ?> message)) {
                return;
            }
            boolean trusted = message instanceof HttpLogMessage;
            IndexedReadOnlyStringMap data = message.getIndexedReadOnlyStringMap();
            MaskingRules rules = MaskingRules.current();
            boolean separator = succeedingEntry;
            for (int i = 0; i < data.size(); i++) {
                String key = data.getKeyAt(i);
                Object value = data.getValueAt(i);
                if (separator) {
                    writer.writeSeparator();
                }
                separator = true;
                if (trusted) {
                    writer.writeObjectKey(key);
                    writer.writeValue(value);
                } else {
                    writer.writeObjectKey(prefix + key);
                    writer.writeString(maskEntry(rules, key, value));
                }
            }
        }

        private static String maskEntry(final MaskingRules rules, final String key, final Object value) {
            try {
                return LogTextMasker.maskField(key, String.valueOf(value), rules);
            } catch (RuntimeException | StackOverflowError e) {
                return "[masking failed]";
            }
        }
    }
}
