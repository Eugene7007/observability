package uz.b2c.kapitalbank.commons.observability;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonStreamContext;
import com.fasterxml.jackson.core.JsonToken;

import uz.b2c.kapitalbank.commons.observability.MaskingRules.Rule;

import java.io.IOException;
import java.io.StringWriter;

/**
 * Маскирует JSON потоково: один проход парсер → генератор, без построения дерева и без регулярок.
 * <p>
 * На вход подаётся уже обрезанный до лимита фрагмент тела. Если фрагмент обрывается посреди JSON,
 * в результат попадает всё, что успело разобраться, и метка {@link #TRUNCATED_MARK}. Значение,
 * оборванное на середине (например, половина номера карты), парсер не отдаёт, поэтому в лог оно не попадает.
 * Тело, которое не разбирается как JSON, в лог не пишется вовсе — только его размер.
 * <p>
 * Правила — {@link MaskingRules}: базовые правила банка плюс поля команды. Кроме полей с известными именами,
 * каждое строковое значение проходит через {@link LogTextMasker} (номера карт и телефоны в тексте, вложенный
 * JSON или XML в строке), а числовое значение из 13–19 цифр с верной контрольной суммой маскируется как карта.
 * Это не отключается.
 */
public final class JsonBodyMasker {

    static final String HIDDEN = LogTextMasker.HIDDEN;
    static final String TRUNCATED_MARK = LogTextMasker.TRUNCATED_MARK;

    private final JsonFactory factory = new JsonFactory();
    private final MaskingRules rules;

    public JsonBodyMasker(final MaskingRules rules) {
        this.rules = rules;
    }

    /** Базовые правила банка плюс поля команды из конфигурации. */
    public JsonBodyMasker(final HttpLogProperties.Masking masking) {
        this(MaskingRules.of(masking));
    }

    /**
     * @param data      буфер с телом (или его началом)
     * @param length    сколько байт из буфера использовать
     * @param truncated true, если тело было длиннее и обрезано
     * @return замаскированный JSON одной строкой, либо описание, если тело не JSON
     */
    public String mask(final byte[] data, final int length, final boolean truncated) {
        StringWriter out = new StringWriter(Math.min(length, 1 << 16) + TRUNCATED_MARK.length());
        String result;
        try (JsonParser parser = factory.createParser(data, 0, length)) {
            JsonGenerator generator = factory.createGenerator(out);
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
            try {
                copy(parser, generator, truncated ? length : -1);
                generator.flush();
                result = truncated ? out + TRUNCATED_MARK : out.toString();
            } catch (IOException e) {
                generator.flush();
                result = truncated ? out + TRUNCATED_MARK : "[unparseable body, " + length + " bytes]";
            } finally {
                // возвращает буферы генератора в BufferRecycler; незакрытые объекты не дописываются
                generator.close();
            }
        } catch (IOException e) {
            result = "[unparseable body, " + length + " bytes]";
        }
        return result;
    }

    /**
     * @param cutAt длина обрезанного фрагмента или -1: число, на котором фрагмент оборвался, — часть номера,
     *              и в лог оно не попадает
     */
    private void copy(final JsonParser parser, final JsonGenerator generator, final int cutAt) throws IOException {
        JsonToken token = parser.nextToken();
        while (token != null) {
            if (token == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                Rule rule = ruleFor(parser, name);
                // имя поля тоже может быть ПДн (ключи map: номер телефона, карты)
                generator.writeFieldName(LogTextMasker.mask(name, rules));
                JsonToken value = parser.nextToken();
                if (value == null) {
                    break;
                }
                if (value.isNumeric() && endsAtCut(parser, cutAt)) {
                    generator.writeString(HIDDEN);
                } else if (rule == null) {
                    copyToken(parser, generator, value);
                } else {
                    applyRule(rule, parser, generator, value);
                }
            } else if (token.isNumeric() && endsAtCut(parser, cutAt)) {
                generator.writeString(HIDDEN);
            } else {
                copyToken(parser, generator, token);
            }
            token = parser.nextToken();
        }
    }

    private static boolean endsAtCut(final JsonParser parser, final int cutAt) {
        return cutAt >= 0 && parser.getCurrentLocation().getByteOffset() >= cutAt;
    }

    private void copyToken(final JsonParser parser, final JsonGenerator generator, final JsonToken token)
        throws IOException {
        if (token == JsonToken.VALUE_STRING) {
            generator.writeString(LogTextMasker.mask(parser.getText(), rules));
        } else if (token == JsonToken.VALUE_NUMBER_INT && LogTextMasker.isPan(parser.getText())) {
            generator.writeString(LogTextMasker.maskPan(parser.getText()));
        } else {
            generator.copyCurrentEvent(parser);
        }
    }

    private void applyRule(final Rule rule, final JsonParser parser, final JsonGenerator generator,
                           final JsonToken value) throws IOException {
        if (value == JsonToken.VALUE_NULL) {
            generator.writeNull();
        } else if (value.isStructStart()) {
            if (rule == Rule.DIGITS || rule == Rule.CODE || rule == Rule.URL || rule == Rule.EMAIL) {
                generator.copyCurrentEvent(parser);
            } else {
                parser.skipChildren();
                generator.writeString(HIDDEN);
            }
        } else if (value == JsonToken.VALUE_STRING) {
            generator.writeString(LogTextMasker.maskValue(rule, parser.getText(), true, rules));
        } else if (value == JsonToken.VALUE_TRUE || value == JsonToken.VALUE_FALSE) {
            // признаки true/false не ПДн
            generator.copyCurrentEvent(parser);
        } else if (rule == Rule.DIGITS) {
            // код подтверждения числом скрывается, остальные числа остаются
            if (LogTextMasker.isOneTimeCode(parser.getText())) {
                generator.writeString(HIDDEN);
            } else {
                generator.copyCurrentEvent(parser);
            }
        } else if (rule == Rule.CODE || rule == Rule.URL || rule == Rule.EMAIL) {
            // числовой код ошибки остаётся
            generator.copyCurrentEvent(parser);
        } else {
            generator.writeString(LogTextMasker.maskValue(rule, parser.getText(), false, rules));
        }
    }

    private Rule ruleFor(final JsonParser parser, final String name) {
        Rule rule = rules.ruleFor(name);
        if (rule == null && "name".equalsIgnoreCase(name) && personObject(parser.getParsingContext())) {
            rule = Rule.NAME;
        }
        if (rule == null && !rules.pathRules().isEmpty()) {
            for (MaskingRules.PathRule pathRule : rules.pathRules()) {
                if (matchesPath(parser.getParsingContext(), pathRule.segments())) {
                    rule = pathRule.rule();
                    break;
                }
            }
        }
        return rule;
    }

    /** Поле {@code name} внутри объекта человека: {"client":{"name":"…"}}, {"card":{"holder":{"name":…}}}. */
    private static boolean personObject(final JsonStreamContext current) {
        JsonStreamContext parent = current == null ? null : current.getParent();
        while (parent != null && !parent.inObject()) {
            parent = parent.getParent();
        }
        String name = parent == null ? null : parent.getCurrentName();
        return name != null && MaskingRules.PERSON_OBJECTS.contains(MaskingRules.normalize(name));
    }

    /**
     * Сравнивает конец пути к текущему полю с сегментами правила. Уровни массивов пропускаются.
     */
    private static boolean matchesPath(final JsonStreamContext current, final String[] segments) {
        JsonStreamContext context = current;
        int index = segments.length - 1;
        boolean matches = true;
        while (index >= 0 && matches) {
            while (context != null && !context.inObject()) {
                context = context.getParent();
            }
            String name = context == null ? null : context.getCurrentName();
            matches = name != null && MaskingRules.normalize(name).equals(segments[index]);
            index--;
            context = context == null ? null : context.getParent();
        }
        return matches;
    }
}
