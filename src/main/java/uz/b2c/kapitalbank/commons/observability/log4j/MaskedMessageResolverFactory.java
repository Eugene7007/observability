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
import org.apache.logging.log4j.message.Message;
import uz.b2c.kapitalbank.commons.observability.HttpLogMessage;
import uz.b2c.kapitalbank.commons.observability.LogTextMasker;

/**
 * Резолвер JSON-шаблона {@code "$resolver": "maskedMessage"}: текст сообщения после маскирования ПДн.
 * Заменяет стандартный {@code message}. Если маскирование почему-либо упало, пишется заглушка, а не исходный текст:
 * запись не теряется и ПДн не утекают. Записи HTTP-логирования не маскируются повторно: их тела уже
 * замаскированы при захвате, а в тексте сообщения только метод, путь и статус.
 */
@Plugin(name = "MaskedMessageResolverFactory", category = TemplateResolverFactory.CATEGORY)
public final class MaskedMessageResolverFactory implements EventResolverFactory {

    private static final MaskedMessageResolverFactory INSTANCE = new MaskedMessageResolverFactory();

    private static final EventResolver RESOLVER = new EventResolver() {
        @Override
        public void resolve(final LogEvent event, final JsonWriter jsonWriter) {
            Message message = event.getMessage();
            if (message == null) {
                jsonWriter.writeNull();
            } else if (message instanceof HttpLogMessage http) {
                jsonWriter.writeString(http.summary());
            } else {
                jsonWriter.writeString(LogTextMasker.maskOrPlaceholder(message.getFormattedMessage()));
            }
        }
    };

    private MaskedMessageResolverFactory() {
    }

    @PluginFactory
    public static MaskedMessageResolverFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public String getName() {
        return "maskedMessage";
    }

    @Override
    public EventResolver create(final EventResolverContext context, final TemplateResolverConfig config) {
        return RESOLVER;
    }
}
