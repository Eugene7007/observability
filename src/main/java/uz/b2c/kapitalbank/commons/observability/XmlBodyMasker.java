package uz.b2c.kapitalbank.commons.observability;

import uz.b2c.kapitalbank.commons.observability.MaskingRules.Rule;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Маскирует XML (в том числе SOAP) потоково на StAX из JDK: один проход чтение → запись, без построения дерева.
 * <p>
 * На вход подаётся уже обрезанный до лимита фрагмент тела. Если фрагмент обрывается посреди XML,
 * в результат попадает всё, что успело разобраться, и метка {@link #TRUNCATED_MARK}; текст оборванного элемента
 * не выводится. Тело, которое не разбирается как XML, в лог не пишется — только его размер. DTD и внешние
 * сущности не обрабатываются (защита от XXE).
 * <p>
 * Правила — {@link MaskingRules}, те же, что для JSON: по имени элемента или атрибута, по пути, {@code name}
 * внутри элемента человека. Правило элемента действует и на вложенные элементы. Формат «имя–значение»
 * ({@code <param name="cvv">123</param>}, {@code <Property Key="Password" Value="..."/>}) распознаётся по атрибутам
 * name/key/field/param. Текст без правила проходит через {@link LogTextMasker}: номера карт и телефоны в тексте
 * маскируются всегда, в том числе номер, разрезанный разметкой ({@code 86001234<b/>56781233}).
 * <p>
 * Комментарии и processing instructions в лог не попадают: в них бывают те же ПДн, а для разбора они не нужны.
 * Используется реализация StAX из JDK, а не та, что окажется в classpath сервиса.
 */
public final class XmlBodyMasker {

    static final String HIDDEN = LogTextMasker.HIDDEN;
    static final String TRUNCATED_MARK = LogTextMasker.TRUNCATED_MARK;

    /** Атрибуты, значение которых — имя поля в формате «имя–значение». */
    private static final java.util.Set<String> KEY_ATTRIBUTES = java.util.Set.of("name", "key", "field", "param");

    private final MaskingRules rules;
    private final XMLInputFactory inputFactory;
    private final XMLOutputFactory outputFactory;

    public XmlBodyMasker(final MaskingRules rules) {
        this.rules = rules;
        this.inputFactory = createInputFactory();
        this.outputFactory = XMLOutputFactory.newDefaultFactory();
    }

    /**
     * Базовые правила банка плюс поля команды из конфигурации.
     */
    public XmlBodyMasker(final HttpLogProperties.Masking masking) {
        this(MaskingRules.of(masking));
    }

    private static XMLInputFactory createInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        // ошибки разбора не печатаются в stderr в обход JSON-формата логов
        factory.setXMLReporter((message, type, info, location) -> { });

        // Не разрешаем внешние сущности и DTD во избежание XXE.
        setIfSupported(factory, XMLInputFactory.SUPPORT_DTD, false);
        setIfSupported(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setIfSupported(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        setIfSupported(factory, XMLInputFactory.IS_COALESCING, true);

        return factory;
    }

    private static void setIfSupported(final XMLInputFactory factory,
                                       final String property,
                                       final Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // Конкретная реализация StAX может не поддерживать это свойство.
        }
    }

    /**
     * @param data      буфер с телом (или его началом)
     * @param length    сколько байт из буфера использовать
     * @param truncated true, если тело было длиннее и обрезано
     * @return замаскированный XML одной строкой либо описание ошибки
     */
    public String mask(final byte[] data, final int length, final boolean truncated) {
        return mask(data, length, truncated, null);
    }

    /**
     * @param charset кодировка из Content-Type или {@code null}: тогда по объявлению {@code <?xml encoding?>}
     */
    public String mask(final byte[] data, final int length, final boolean truncated, final String charset) {
        if (data == null || length <= 0 || length > data.length) {
            return "[unparseable body, " + Math.max(length, 0) + " bytes]";
        }

        StringWriter out = new StringWriter(Math.min(length, 1 << 16) + TRUNCATED_MARK.length());

        XMLStreamReader reader = null;
        XMLStreamWriter writer = null;

        try {
            // байты декодируются заранее: ошибка кодировки — это [unparseable], а не сообщение парсера в stderr
            String text = decode(data, length, truncated, charset);
            if (text == null) {
                return "[unparseable body, " + length + " bytes]";
            }
            reader = inputFactory.createXMLStreamReader(new java.io.StringReader(text));
            writer = outputFactory.createXMLStreamWriter(out);

            copy(reader, writer);
            writer.flush();

            String masked = maskDigitsAcrossMarkup(out.toString());
            return truncated ? masked + TRUNCATED_MARK : masked;
        } catch (XMLStreamException | RuntimeException e) {
            if (truncated) {
                // При обрезанном XML сохраняем только ту часть, которую StAX успел разобрать.
                try {
                    if (writer != null) {
                        writer.flush();
                    }
                } catch (XMLStreamException ignored) {
                    // Возвращаем уже сформированную безопасную часть.
                }
                return maskDigitsAcrossMarkup(out.toString()) + TRUNCATED_MARK;
            }
            return "[unparseable body, " + length + " bytes]";
        } finally {
            closeQuietly(reader);
            closeQuietly(writer);
        }
    }

    /**
     * Текст тела в кодировке из Content-Type, BOM или объявления {@code <?xml encoding?>}, по умолчанию UTF-8.
     * {@code null}, если байты не соответствуют кодировке. У обрезанного тела отбрасывается оборванный символ.
     */
    static String decode(final byte[] data, final int length, final boolean truncated, final String header) {
        java.nio.charset.Charset charset = charsetOf(data, length, header);
        int end = length;
        for (int attempt = 0; attempt < (truncated ? 4 : 1) && end > 0; attempt++, end--) {
            try {
                String text = charset.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data, 0, end)).toString();
                // BOM в начале строки парсер принимает за текст до корня
                return text.startsWith("\uFEFF") ? text.substring(1) : text;
            } catch (java.nio.charset.CharacterCodingException e) {
                // у обрезанного тела пробуем без последних байтов оборванного символа
            }
        }
        return null;
    }

    private static java.nio.charset.Charset charsetOf(final byte[] data, final int length, final String header) {
        String name = header;
        if (name == null && length >= 2 && ((data[0] == (byte) 0xFE && data[1] == (byte) 0xFF)
            || (data[0] == (byte) 0xFF && data[1] == (byte) 0xFE))) {
            name = "UTF-16";
        }
        if (name == null) {
            String prolog = new String(data, 0, Math.min(length, 200), java.nio.charset.StandardCharsets.ISO_8859_1);
            int start = prolog.startsWith("\uFEFF") || prolog.startsWith("\u00EF\u00BB\u00BF") ? 3 : 0;
            if (prolog.startsWith("<?xml", start)) {
                int close = prolog.indexOf("?>");
                int at = prolog.indexOf("encoding", start);
                if (at > 0 && (close < 0 || at < close)) {
                    int quote = at + 8;
                    while (quote < prolog.length() && prolog.charAt(quote) != '"' && prolog.charAt(quote) != '\'') {
                        quote++;
                    }
                    int endQuote = quote + 1 < prolog.length() ? prolog.indexOf(prolog.charAt(quote), quote + 1) : -1;
                    if (endQuote > quote) {
                        name = prolog.substring(quote + 1, endQuote);
                    }
                }
            }
        }
        try {
            return name != null && java.nio.charset.Charset.isSupported(name)
                ? java.nio.charset.Charset.forName(name) : java.nio.charset.StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            return java.nio.charset.StandardCharsets.UTF_8;
        }
    }

    private void copy(final XMLStreamReader reader,
                      final XMLStreamWriter writer) throws XMLStreamException {
        Deque<ElementFrame> stack = new ArrayDeque<>();
        StringBuilder text = new StringBuilder();

        if (reader.getEventType() == XMLStreamConstants.START_DOCUMENT) {
            writeStartDocument(reader, writer);
        }

        while (reader.hasNext()) {
            int event = reader.next();

            // Собираем соседние текстовые события целиком, чтобы не пропустить значение,
            // разделённое парсером на несколько CHARACTERS/CDATA-событий.
            if (event == XMLStreamConstants.CHARACTERS
                || event == XMLStreamConstants.CDATA
                || event == XMLStreamConstants.SPACE
                || event == XMLStreamConstants.ENTITY_REFERENCE) {
                if (reader.getText() != null) {
                    text.append(reader.getText());
                }
                continue;
            }
            if (event == XMLStreamConstants.COMMENT || event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                // не пишутся, и текст по обе стороны склеивается: 8600123456<!--x-->781233 — один номер
                continue;
            }

            flushText(text, writer, stack);

            switch (event) {
                case XMLStreamConstants.START_DOCUMENT:
                    writeStartDocument(reader, writer);
                    break;
                case XMLStreamConstants.START_ELEMENT:
                    writeStartElement(reader, writer, stack);
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    writer.writeEndElement();
                    if (!stack.isEmpty()) {
                        stack.pop();
                    }
                    break;
                case XMLStreamConstants.END_DOCUMENT:
                    writer.writeEndDocument();
                    break;
                default:
                    // DTD и прочие неподдерживаемые события намеренно не копируются.
                    break;
            }
        }

        flushText(text, writer, stack);
        writer.flush();
    }

    private void writeStartElement(final XMLStreamReader reader,
                                   final XMLStreamWriter writer,
                                   final Deque<ElementFrame> stack) throws XMLStreamException {
        String prefix = nullToEmpty(reader.getPrefix());
        String namespace = nullToEmpty(reader.getNamespaceURI());
        String localName = reader.getLocalName();

        if (namespace.isEmpty()) {
            writer.writeStartElement(localName);
        } else {
            writer.writeStartElement(prefix, localName, namespace);
        }

        // Сохраняем объявления namespace на том элементе, где они встретились.
        for (int i = 0; i < reader.getNamespaceCount(); i++) {
            String nsPrefix = reader.getNamespacePrefix(i);
            String nsUri = nullToEmpty(reader.getNamespaceURI(i));
            if (nsPrefix == null || nsPrefix.isEmpty()) {
                writer.writeDefaultNamespace(nsUri);
            } else {
                writer.writeNamespace(nsPrefix, nsUri);
            }
        }

        String elementName = localName;
        Rule elementRule = ruleFor(elementName, stack, true);
        int keyAttribute = -1;
        if (elementRule == null) {
            // формат «имя–значение»: <param name="cvv">123</param>, <Property Key="Password" Value="..."/>
            for (int i = 0; i < reader.getAttributeCount() && keyAttribute < 0; i++) {
                if (KEY_ATTRIBUTES.contains(reader.getAttributeLocalName(i).toLowerCase(java.util.Locale.ROOT))) {
                    Rule keyed = rules.ruleForUncached(reader.getAttributeValue(i));
                    if (keyed != null) {
                        elementRule = keyed;
                        keyAttribute = i;
                    }
                }
            }
        }
        ElementFrame frame = new ElementFrame(elementName, elementRule);
        stack.push(frame);

        // XML attributes are first-class values and must be masked independently.
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String attrPrefix = nullToEmpty(reader.getAttributePrefix(i));
            String attrNamespace = nullToEmpty(reader.getAttributeNamespace(i));
            String attrLocalName = reader.getAttributeLocalName(i);
            String attrValue = reader.getAttributeValue(i);

            Rule attrRule = i == keyAttribute ? null : ruleFor(attrLocalName, stack, false);
            if (attrRule == null && i != keyAttribute) {
                for (ElementFrame ancestor : stack) {
                    if (ancestor.rule != null) {
                        attrRule = ancestor.rule;
                        break;
                    }
                }
            }
            String safeValue = attrRule == null
                ? LogTextMasker.mask(attrValue, rules)
                : LogTextMasker.maskValue(attrRule, attrValue, true, rules);

            if (attrNamespace.isEmpty()) {
                writer.writeAttribute(attrLocalName, safeValue);
            } else {
                writer.writeAttribute(attrPrefix, attrNamespace, attrLocalName, safeValue);
            }
        }
    }

    private void flushText(final StringBuilder text,
                           final XMLStreamWriter writer,
                           final Deque<ElementFrame> stack) throws XMLStreamException {
        if (text.length() == 0) {
            return;
        }

        String value = text.toString();
        text.setLength(0);

        Rule rule = null;
        for (ElementFrame frame : stack) {
            if (frame.rule != null) {
                rule = frame.rule;
                break;
            }
        }

        String safeValue;
        if (rule == null) {
            safeValue = LogTextMasker.mask(value, rules);
        } else if (rule == Rule.CODE) {
            // в XML всё — текст: OTP из 4–8 цифр скрывается, коды результата вроде <code>0</code> остаются
            String trimmed = value.trim();
            safeValue = LogTextMasker.isOneTimeCode(trimmed) ? HIDDEN : LogTextMasker.mask(value, rules);
        } else {
            safeValue = LogTextMasker.maskValue(rule, value, true, rules);
        }

        writer.writeCharacters(safeValue);
    }

    private Rule ruleFor(final String name,
                         final Deque<ElementFrame> stack,
                         final boolean includeCurrentElement) {
        Rule rule = rules.ruleFor(name);

        if (rule == null && "name".equalsIgnoreCase(name) && isPersonElement(stack, includeCurrentElement)) {
            rule = Rule.NAME;
        }

        // поле по пути от команды: более строгое из правил, база не ослабляет то, что команда скрыла
        if (rule != Rule.HIDE && !rules.pathRules().isEmpty()) {
            String[] path = buildPath(stack, name, includeCurrentElement);
            for (MaskingRules.PathRule pathRule : rules.pathRules()) {
                if (matchesPath(path, pathRule.segments())) {
                    rule = MaskingRules.stricter(rule, pathRule.rule());
                }
            }
        }

        return rule;
    }

    private static boolean isPersonElement(final Deque<ElementFrame> stack,
                                           final boolean includeCurrentElement) {
        // For an element, the parent is the current stack top; for an attribute,
        // the current element itself is the containing object.
        ElementFrame parent = stack.peek();
        return parent != null
            && MaskingRules.PERSON_OBJECTS.contains(MaskingRules.normalize(parent.name));
    }

    private static String[] buildPath(final Deque<ElementFrame> stack,
                                      final String name,
                                      final boolean includeCurrentElement) {
        Deque<String> names = new ArrayDeque<>();
        // Deque iteration is from current element to root; prepend to obtain root-to-leaf order.
        for (ElementFrame frame : stack) {
            names.addFirst(frame.name);
        }
        if (includeCurrentElement) {
            names.addLast(name);
        }
        return names.toArray(new String[0]);
    }

    private static boolean matchesPath(final String[] path, final String[] segments) {
        if (segments.length > path.length) {
            return false;
        }

        int pathIndex = path.length - 1;
        int segmentIndex = segments.length - 1;

        while (segmentIndex >= 0) {
            if (!MaskingRules.normalize(path[pathIndex])
                .equals(MaskingRules.normalize(segments[segmentIndex]))) {
                return false;
            }
            pathIndex--;
            segmentIndex--;
        }

        return true;
    }

    private static void writeStartDocument(final XMLStreamReader reader,
                                           final XMLStreamWriter writer) throws XMLStreamException {
        if (reader.getVersion() == null) {
            // в исходном теле не было объявления <?xml ...?> — не добавляем его в лог
            return;
        }
        String version = reader.getVersion();
        String encoding = reader.getCharacterEncodingScheme();

        if (encoding == null || encoding.isEmpty()) {
            writer.writeStartDocument(version);
        } else {
            writer.writeStartDocument(encoding, version);
        }
    }

    /**
     * Номер карты, разрезанный разметкой: 86001234&lt;b/&gt;56781233. Цифры вне тегов собираются в группы
     * (группы разделяет пробел, дефис или разметка); любая последовательность соседних групп, которая пересекает
     * разметку и содержит 13–19 цифр с верной контрольной суммой, считается номером: все её цифры, кроме первых 6
     * и последних 4, заменяются на '*'.
     */
    static String maskDigitsAcrossMarkup(final String xml) {
        // группы цифр вне тегов; null разделяет цепочки, markupBefore — была ли разметка перед группой
        java.util.List<int[]> groups = new java.util.ArrayList<>();
        java.util.List<Boolean> markupBefore = new java.util.ArrayList<>();
        int length = xml.length();
        int lastEnd = -1;
        int i = 0;
        while (i < length) {
            char c = xml.charAt(i);
            if (c == '<') {
                int close = xml.indexOf('>', i);
                i = close < 0 ? length : close + 1;
                continue;
            }
            if (!Character.isDigit(c)) {
                i++;
                continue;
            }
            int end = i;
            while (end < length && Character.isDigit(xml.charAt(end))) {
                end++;
            }
            int gap = lastEnd < 0 ? -1 : gapKind(xml, lastEnd, i);
            if (gap < 0) {
                groups.add(null);
                markupBefore.add(false);
            }
            groups.add(new int[] {i, end});
            markupBefore.add(gap == 1);
            lastEnd = end;
            i = end;
        }
        StringBuilder result = null;
        int g = 0;
        while (g < groups.size()) {
            if (groups.get(g) == null) {
                g++;
                continue;
            }
            int matchedEnd = -1;
            int digits = 0;
            boolean crossesMarkup = false;
            for (int k = g; k < groups.size() && groups.get(k) != null; k++) {
                int[] group = groups.get(k);
                digits += group[1] - group[0];
                crossesMarkup |= k > g && markupBefore.get(k);
                if (digits > 19) {
                    break;
                }
                if (digits >= 13 && crossesMarkup && luhn(xml, groups.subList(g, k + 1))) {
                    matchedEnd = k;
                    break;
                }
            }
            if (matchedEnd < 0) {
                g++;
                continue;
            }
            if (result == null) {
                result = new StringBuilder(xml);
            }
            int seen = 0;
            for (int k = g; k <= matchedEnd; k++) {
                int[] group = groups.get(k);
                for (int p = group[0]; p < group[1]; p++) {
                    if (seen >= 6 && seen < digits - 4) {
                        result.setCharAt(p, '*');
                    }
                    seen++;
                }
            }
            g = matchedEnd + 1;
        }
        return result == null ? xml : result.toString();
    }

    /**
     * Что между двумя группами цифр: -1 — текст (цепочка прерывается), 0 — один пробел или дефис, 1 — разметка
     * (теги, возможно с одним разделителем).
     */
    private static int gapKind(final String xml, final int from, final int to) {
        int separators = 0;
        boolean markup = false;
        int i = from;
        while (i < to) {
            char c = xml.charAt(i);
            if (c == '<') {
                int close = xml.indexOf('>', i);
                if (close < 0 || close >= to) {
                    return -1;
                }
                markup = true;
                i = close + 1;
            } else if ((c == ' ' || c == '-') && separators == 0) {
                separators++;
                i++;
            } else {
                return -1;
            }
        }
        return markup ? 1 : 0;
    }

    private static boolean luhn(final String xml, final java.util.List<int[]> groups) {
        StringBuilder digits = new StringBuilder();
        for (int[] group : groups) {
            digits.append(xml, group[0], group[1]);
        }
        return LogTextMasker.isPan(digits.toString());
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    private static void closeQuietly(final XMLStreamReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (XMLStreamException ignored) {
                // Ignore close errors while returning the masked body.
            }
        }
    }

    private static void closeQuietly(final XMLStreamWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (XMLStreamException ignored) {
                // Ignore close errors while returning the masked body.
            }
        }
    }

    private static final class ElementFrame {
        private final String name;
        private final Rule rule;

        private ElementFrame(final String name, final Rule rule) {
            this.name = name;
            this.rule = rule;
        }
    }
}
