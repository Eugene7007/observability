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
 * <p>
 * Формат «имя–значение» ({@code [{"name":"password","value":"..."}]}) распознаётся при любом порядке полей:
 * если в объекте поле name/key/field/param называет ПДн, то соседнее поле value/val маскируется по этому правилу.
 * Для этого тело, в котором есть поле value/val, разбирается дважды: сначала собираются правила объектов.
 */
public final class JsonBodyMasker {

    static final String HIDDEN = LogTextMasker.HIDDEN;
    static final String TRUNCATED_MARK = LogTextMasker.TRUNCATED_MARK;

    private static final java.util.Set<String> KEY_FIELDS = java.util.Set.of("name", "key", "field", "param");
    private static final java.util.Set<String> VALUE_FIELDS = java.util.Set.of("value", "val");

    /**
     * Имена полей не канонизируются: тела приходят извне, и общая таблица имён фабрики росла бы от каждого нового
     * имени (копирование таблицы на каждый парсер, удержание памяти).
     */
    private final JsonFactory factory = new com.fasterxml.jackson.core.JsonFactoryBuilder()
        .disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES)
        .disable(JsonFactory.Feature.INTERN_FIELD_NAMES)
        .build();
    private final MaskingRules rules;

    public JsonBodyMasker(final MaskingRules rules) {
        this.rules = rules;
    }

    /** Базовые правила банка плюс поля команды из конфигурации. */
    public JsonBodyMasker(final HttpLogProperties.Masking masking) {
        this(MaskingRules.of(masking));
    }

    MaskingRules rules() {
        return rules;
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
        java.util.Map<Long, Rule> keyed = mentionsValueField(data, length) ? keyedObjects(data, length) : null;
        try (JsonParser parser = factory.createParser(data, 0, length)) {
            JsonGenerator generator = factory.createGenerator(out);
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
            try {
                copy(parser, generator, truncated ? new int[] {length, utf16Length(data, length)} : null, keyed);
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
     * @param cutAt длина обрезанного фрагмента в байтах и в символах или null: число, на котором фрагмент оборвался, — часть номера,
     *              и в лог оно не попадает
     */
    private void copy(final JsonParser parser, final JsonGenerator generator, final int[] cutAt,
                      final java.util.Map<Long, Rule> keyed) throws IOException {
        JsonToken token = parser.nextToken();
        while (token != null) {
            if (token == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                Rule rule = ruleFor(parser, name);
                if (rule == null && keyed != null && VALUE_FIELDS.contains(MaskingRules.lower(name))) {
                    // правило из поля name/key того же объекта: {"value":"123","name":"cvv"}; объект, оборванный
                    // обрезкой до конца, мог не успеть назвать поле — его value скрывается
                    rule = keyed.get(objectKey(parser.getParsingContext()));
                    if (rule == null && !keyed.containsKey(CLOSED | objectKey(parser.getParsingContext()))) {
                        rule = Rule.HIDE;
                    }
                }
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

    /** Есть ли в теле поле value/val — только тогда нужен первый проход. */
    private static boolean mentionsValueField(final byte[] data, final int length) {
        for (int i = 0; i + 4 < length; i++) {
            if (data[i] == '"' && (data[i + 1] | 0x20) == 'v' && (data[i + 2] | 0x20) == 'a' && (data[i + 3] | 0x20) == 'l') {
                return true;
            }
        }
        return false;
    }

    /**
     * Первый проход: для каждого объекта, где поле name/key/field/param называет ПДн, — правило по позиции начала
     * объекта. Обрезанное или битое тело разбирается до места ошибки.
     */
    private java.util.Map<Long, Rule> keyedObjects(final byte[] data, final int length) {
        java.util.Map<Long, Rule> keyed = new java.util.HashMap<>();
        java.util.Deque<Long> open = new java.util.ArrayDeque<>();
        try (JsonParser parser = factory.createParser(data, 0, length)) {
            JsonToken token = parser.nextToken();
            while (token != null) {
                if (token == JsonToken.START_OBJECT) {
                    open.push(objectKey(parser.getParsingContext()));
                } else if (token == JsonToken.END_OBJECT && !open.isEmpty()) {
                    // объект дочитан до конца: отметка, что все его поля известны
                    keyed.put(CLOSED | open.pop(), Rule.HIDE);
                } else if (token == JsonToken.FIELD_NAME && KEY_FIELDS.contains(MaskingRules.lower(parser.currentName()))
                    && parser.nextToken() == JsonToken.VALUE_STRING) {
                    Rule named = rules.ruleForUncached(parser.getText());
                    if (named != null) {
                        keyed.putIfAbsent(objectKey(parser.getParsingContext()), named);
                    }
                }
                token = parser.nextToken();
            }
        } catch (IOException e) {
            // обрезанное тело: правила объектов до места обрыва уже собраны
        }
        return keyed;
    }

    /** Бит отметки «объект закрыт» в ключе {@link #keyedObjects}. */
    private static final long CLOSED = 1L << 62;

    /** Позиция начала объекта (строка и столбец открывающей скобки) — одинакова в обоих проходах. */
    private static long objectKey(final JsonStreamContext context) {
        com.fasterxml.jackson.core.JsonLocation start =
            context.startLocation(com.fasterxml.jackson.core.io.ContentReference.unknown());
        return start == null ? -1L : ((long) start.getLineNr() << 32) | (start.getColumnNr() & 0xFFFFFFFFL);
    }

    /**
     * Число на конце обрезанного фрагмента. Парсер без канонизации имён читает символы, а не байты, поэтому
     * место обрыва сравнивается в символах UTF-16.
     */
    private static boolean endsAtCut(final JsonParser parser, final int[] cutAt) {
        com.fasterxml.jackson.core.JsonLocation location = parser.getCurrentLocation();
        // байтовый парсер даёт смещение в байтах, символьный — в символах; проверяется то, что известно
        return cutAt != null && (location.getByteOffset() >= cutAt[0] || location.getCharOffset() >= cutAt[1]);
    }

    /** Сколько символов UTF-16 в первых {@code length} байтах UTF-8 (неполный символ на конце не считается). */
    private static int utf16Length(final byte[] data, final int length) {
        int chars = 0;
        int i = 0;
        while (i < length) {
            int b = data[i] & 0xFF;
            int size = b < 0x80 ? 1 : b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1;
            if (i + size > length) {
                break;
            }
            chars += size == 4 ? 2 : 1;
            i += size;
        }
        return chars;
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
