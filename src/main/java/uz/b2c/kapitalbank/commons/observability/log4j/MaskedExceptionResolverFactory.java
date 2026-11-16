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
import uz.b2c.kapitalbank.commons.observability.LogTextMasker;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * Резолвер JSON-шаблона {@code "$resolver": "maskedException"}: исключение события после маскирования ПДн.
 * <p>
 * Сообщения исключений часто содержат данные: например, {@code HttpClientErrorException} включает тело ответа.
 * Поэтому сообщение и стек проходят через {@link LogTextMasker}, а имя класса пишется как есть.
 * <p>
 * Параметры: {@code field} — {@code className}, {@code message} или {@code stackTrace};
 * для стека — {@code truncationPoints} (строки; блок стека обрезается после первой строки, содержащей любую из них)
 * и {@code truncationSuffix}.
 */
@Plugin(name = "MaskedExceptionResolverFactory", category = TemplateResolverFactory.CATEGORY)
public final class MaskedExceptionResolverFactory implements EventResolverFactory {

    private static final MaskedExceptionResolverFactory INSTANCE = new MaskedExceptionResolverFactory();

    private MaskedExceptionResolverFactory() {
    }

    @PluginFactory
    public static MaskedExceptionResolverFactory getInstance() {
        return INSTANCE;
    }

    @Override
    public String getName() {
        return "maskedException";
    }

    @Override
    public EventResolver create(final EventResolverContext context, final TemplateResolverConfig config) {
        String field = config.getString("field");
        if ("className".equals(field)) {
            return new Resolver() {
                @Override
                void write(final Throwable thrown, final JsonWriter writer) {
                    writer.writeString(thrown.getClass().getName());
                }
            };
        }
        if ("message".equals(field)) {
            return new Resolver() {
                @Override
                void write(final Throwable thrown, final JsonWriter writer) {
                    String message = thrown.getMessage();
                    if (message == null) {
                        writer.writeNull();
                    } else {
                        writer.writeString(LogTextMasker.maskOrPlaceholder(message));
                    }
                }
            };
        }
        if ("stackTrace".equals(field)) {
            List<String> points = config.exists("truncationPoints")
                ? List.copyOf(config.getList("truncationPoints", String.class)) : List.of();
            String suffix = config.getString("truncationSuffix");
            return new Resolver() {
                @Override
                void write(final Throwable thrown, final JsonWriter writer) {
                    writer.writeString(LogTextMasker.maskOrPlaceholder(stackTrace(thrown, points, suffix)));
                }
            };
        }
        throw new IllegalArgumentException("maskedException: неизвестное поле " + field);
    }

    /**
     * Стек одной строкой. Каждый блок ({@code Caused by:}, {@code Suppressed:}) обрезается отдельно после первой
     * строки с одной из {@code points}, поэтому причины исключения не теряются, а повторяющийся хвост фреймворка — да.
     */
    static String stackTrace(final Throwable thrown, final List<String> points, final String suffix) {
        StringWriter out = new StringWriter(2048);
        thrown.printStackTrace(new PrintWriter(out));
        String text = out.toString();
        if (points.stream().noneMatch(text::contains)) {
            return text;
        }
        StringBuilder result = new StringBuilder(text.length());
        boolean skipping = false;
        for (String line : text.split("\n", -1)) {
            boolean blockStart = line.startsWith("Caused by:") || line.trim().startsWith("Suppressed:");
            if (blockStart) {
                skipping = false;
            }
            if (!skipping) {
                result.append(line).append('\n');
                if (points.stream().anyMatch(line::contains)) {
                    skipping = true;
                    if (suffix != null) {
                        result.append('\t').append(suffix).append('\n');
                    }
                }
            }
        }
        return result.toString();
    }

    private abstract static class Resolver implements EventResolver {

        @Override
        public boolean isResolvable(final LogEvent event) {
            return event.getThrown() != null;
        }

        @Override
        public void resolve(final LogEvent event, final JsonWriter writer) {
            Throwable thrown = event.getThrown();
            if (thrown == null) {
                writer.writeNull();
            } else {
                write(thrown, writer);
            }
        }

        abstract void write(Throwable thrown, JsonWriter writer);
    }
}
