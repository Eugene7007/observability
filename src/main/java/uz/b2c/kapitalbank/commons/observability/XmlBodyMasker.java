package uz.b2c.kapitalbank.commons.observability;

import uz.b2c.kapitalbank.commons.observability.MaskingRules.Rule;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import java.io.ByteArrayInputStream;
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
 * внутри элемента человека. Правило элемента действует и на вложенные элементы. Текст без правила проходит
 * через {@link LogTextMasker}: номера карт и телефоны в тексте маскируются всегда.
 */
public final class XmlBodyMasker {

    static final String HIDDEN = LogTextMasker.HIDDEN;
    static final String TRUNCATED_MARK = LogTextMasker.TRUNCATED_MARK;

    private final MaskingRules rules;
    private final XMLInputFactory inputFactory;
    private final XMLOutputFactory outputFactory;

    public XmlBodyMasker(final MaskingRules rules) {
        this.rules = rules;
        this.inputFactory = createInputFactory();
        this.outputFactory = XMLOutputFactory.newFactory();
    }

    /**
     * Базовые правила банка плюс поля команды из конфигурации.
     */
    public XmlBodyMasker(final HttpLogProperties.Masking masking) {
        this(MaskingRules.of(masking));
    }

    private static XMLInputFactory createInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();

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
        if (data == null || length <= 0 || length > data.length) {
            return "[unparseable body, " + Math.max(length, 0) + " bytes]";
        }

        StringWriter out = new StringWriter(Math.min(length, 1 << 16) + TRUNCATED_MARK.length());

        XMLStreamReader reader = null;
        XMLStreamWriter writer = null;

        try {
            reader = inputFactory.createXMLStreamReader(new ByteArrayInputStream(data, 0, length));
            writer = outputFactory.createXMLStreamWriter(out);

            copy(reader, writer);
            writer.flush();

            return truncated ? out + TRUNCATED_MARK : out.toString();
        } catch (XMLStreamException e) {
            if (truncated) {
                // При обрезанном XML сохраняем только ту часть, которую StAX успел разобрать.
                try {
                    if (writer != null) {
                        writer.flush();
                    }
                } catch (XMLStreamException ignored) {
                    // Возвращаем уже сформированную безопасную часть.
                }
                return out + TRUNCATED_MARK;
            }
            return "[unparseable body, " + length + " bytes]";
        } finally {
            closeQuietly(reader);
            closeQuietly(writer);
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
                case XMLStreamConstants.COMMENT:
                    writer.writeComment(reader.getText());
                    break;
                case XMLStreamConstants.PROCESSING_INSTRUCTION:
                    if (reader.getPIData() == null) {
                        writer.writeProcessingInstruction(reader.getPITarget());
                    } else {
                        writer.writeProcessingInstruction(reader.getPITarget(), reader.getPIData());
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
        ElementFrame frame = new ElementFrame(elementName, elementRule);
        stack.push(frame);

        // XML attributes are first-class values and must be masked independently.
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String attrPrefix = nullToEmpty(reader.getAttributePrefix(i));
            String attrNamespace = nullToEmpty(reader.getAttributeNamespace(i));
            String attrLocalName = reader.getAttributeLocalName(i);
            String attrValue = reader.getAttributeValue(i);

            Rule attrRule = ruleFor(attrLocalName, stack, false);
            if (attrRule == null) {
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

        if (rule == null && !rules.pathRules().isEmpty()) {
            String[] path = buildPath(stack, name, includeCurrentElement);
            for (MaskingRules.PathRule pathRule : rules.pathRules()) {
                if (matchesPath(path, pathRule.segments())) {
                    rule = pathRule.rule();
                    break;
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
