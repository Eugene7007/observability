package uz.b2c.kapitalbank.commons.observability;

import uz.b2c.kapitalbank.commons.observability.MaskingRules.Rule;

/**
 * Маскирует ПДн в произвольном тексте: сообщения лога, сообщения и стеки исключений, строковые значения
 * внутри JSON-тел. Это страховочная сетка для того, что разработчик пишет сам: {@code log.info("ответ {}", dto)},
 * toString Lombok, JSON и XML в тексте, исключение RestTemplate с телом ответа.
 * <p>
 * Один проход по строке без регулярных выражений: время линейно от длины, стек не растёт. Распознаётся:
 * <ul>
 *     <li>«поле — значение» для полей из {@link MaskingRules}: {@code "pan":"…"}, {@code \"pan\":\"…\"}
 *     (экранированный JSON), {@code pan=…}, {@code pan: …}, {@code <pan>…</pan>}, {@code &lt;pan&gt;…};</li>
 *     <li>номера карт в любом месте: 13–19 цифр, в том числе группами через пробел или дефис, с проверкой Луна;</li>
 *     <li>узбекские мобильные номера {@code 998XXXXXXXXX} и {@code +998 XX XXX XX XX};</li>
 *     <li>{@code Bearer <токен>}.</li>
 * </ul>
 * Значения без кавычек маскируются «с запасом»: до разделителя, а если в значении пробелы — пока не начнётся
 * следующая пара «поле=».
 */
public final class LogTextMasker {

    static final String HIDDEN = "***";
    static final String TRUNCATED_MARK = "...[truncated]";

    /** Дальше этой длины текст не маскируется, а обрезается: формат лога всё равно обрезает строки раньше. */
    static final int MAX_LENGTH = 32_768;

    private static final int PAN_MIN = 13;
    private static final int PAN_MAX = 19;
    private static final int PAN_HEAD = 6;
    private static final int TAIL = 4;
    private static final int PHONE_DIGITS = 12;
    private static final int MAX_KEY = 64;
    private static final String BEARER = "bearer";
    private static final int MAX_GROUPS = 8;
    private static final int MAX_DEPTH = 2;

    private LogTextMasker() {
    }

    /** Маскирует текст текущими правилами ({@link MaskingRules#current()}). */
    public static String mask(final String text) {
        return mask(text, MaskingRules.current());
    }

    public static String mask(final String text, final MaskingRules rules) {
        return mask(text, rules, 0);
    }

    /**
     * @param depth глубина вложенности: значение поля {@code url} или {@code code} разбирается ещё раз, но не глубже
     *              {@link #MAX_DEPTH} — дальше ищутся только номера карт и телефоны, без пар «поле — значение»
     */
    static String mask(final String text, final MaskingRules rules, final int depth) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (text.length() <= MAX_LENGTH) {
            return new Scan(text, rules, depth).run();
        }
        // обрезка могла разрезать номер: оборванный хвост из цифр не попадает в лог
        int cut = MAX_LENGTH;
        while (cut > 0 && (isDigit(text.charAt(cut - 1)) || text.charAt(cut - 1) == ' ' || text.charAt(cut - 1) == '-')) {
            cut--;
        }
        String head = new Scan(text.substring(0, cut), rules, depth).run();
        return head + (cut < MAX_LENGTH ? HIDDEN : "") + TRUNCATED_MARK;
    }

    /** Безопасная версия для вывода: при любой ошибке маскирования возвращает заглушку, а не исходный текст. */
    public static String maskOrPlaceholder(final String text) {
        try {
            return mask(text);
        } catch (RuntimeException | StackOverflowError e) {
            return "[masking failed, " + (text == null ? 0 : text.length()) + " chars]";
        }
    }

    /** Один проход по тексту; результат собирается, только если что-то замаскировано. */
    private static final class Scan {

        private final String s;
        private final MaskingRules rules;
        private final int n;
        private final int depth;
        private final boolean keys;
        private StringBuilder out;
        private int copied;

        Scan(final String s, final MaskingRules rules, final int depth) {
            this.s = s;
            this.rules = rules;
            this.n = s.length();
            this.depth = depth;
            this.keys = depth < MAX_DEPTH;
        }

        String run() {
            int i = 0;
            while (i < n) {
                char c = s.charAt(i);
                int next;
                if (keys && (c == ':' || c == '=')) {
                    next = keyValue(i, 1);
                } else if (keys && c == '>') {
                    next = xmlValue(i, i + 1);
                } else if (keys && c == '&' && s.startsWith("&gt;", i)) {
                    next = xmlValue(i, i + 4);
                } else if (isDigit(c) || (c == '+' && i + 1 < n && isDigit(s.charAt(i + 1)))) {
                    next = digits(i);
                } else if ((c == 'b' || c == 'B') && s.regionMatches(true, i, BEARER, 0, BEARER.length())) {
                    next = bearer(i);
                } else {
                    next = i + 1;
                }
                i = next;
            }
            if (out == null) {
                return s;
            }
            out.append(s, copied, n);
            return out.toString();
        }

        private void replace(final int from, final int to, final String value) {
            if (out == null) {
                out = new StringBuilder(n + 16);
            }
            out.append(s, copied, from).append(value);
            copied = to;
        }

        // ---------- «поле: значение» и «поле=значение» ----------

        private int keyValue(final int sep, final int sepLength) {
            int keyEnd = sep;
            int j = sep - 1;
            while (j >= 0 && s.charAt(j) == ' ') {
                j--;
            }
            if (j >= 0 && (s.charAt(j) == '"' || s.charAt(j) == '\'')) {
                j--;
                if (j >= 0 && s.charAt(j) == '\\') {
                    j--;
                }
            }
            keyEnd = j + 1;
            int keyStart = keyEnd;
            while (keyStart > 0 && keyEnd - keyStart < MAX_KEY && isKeyChar(s.charAt(keyStart - 1))) {
                keyStart--;
            }
            if (keyStart == keyEnd || (keyStart > 0 && isKeyChar(s.charAt(keyStart - 1)))) {
                return sep + sepLength;
            }
            Rule rule = rules.ruleFor(s.substring(keyStart, keyEnd));
            if (rule == null) {
                return sep + sepLength;
            }
            return value(sep + sepLength, rule);
        }

        /** Значение после разделителя; возвращает позицию, с которой продолжать разбор. */
        private int value(final int from, final Rule rule) {
            int i = from;
            while (i < n && s.charAt(i) == ' ') {
                i++;
            }
            if (i >= n) {
                return i;
            }
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n && s.charAt(i + 1) == '"') {
                int end = s.indexOf("\\\"", i + 2);
                end = end < 0 ? n : end;
                apply(rule, i + 2, end, true);
                return Math.min(n, end + 2);
            }
            if (c == '"' || c == '\'') {
                int end = closingQuote(i + 1, c);
                apply(rule, i + 1, end, true);
                return Math.min(n, end + 1);
            }
            if (c == '{' || c == '[') {
                if (rule == Rule.DIGITS || rule == Rule.CODE || rule == Rule.URL) {
                    // внутрь объекта заходим обычным разбором, без поиска закрывающей скобки
                    return i + 1;
                }
                int end = closingBracket(i, c, c == '{' ? '}' : ']');
                replace(i, end, HIDDEN);
                return end;
            }
            int end = bareEnd(i);
            String value = s.substring(i, end);
            if (!"null".equalsIgnoreCase(value)) {
                apply(rule, i, end, false);
            }
            return end;
        }

        private void apply(final Rule rule, final int from, final int to, final boolean quoted) {
            if (from >= to) {
                return;
            }
            String value = s.substring(from, to);
            String masked = maskValue(rule, value, quoted, rules, depth + 1);
            if (!masked.equals(value)) {
                replace(from, to, masked);
            }
        }

        private int closingQuote(final int from, final char quote) {
            int i = from;
            while (i < n) {
                char c = s.charAt(i);
                if (c == '\\') {
                    i += 2;
                    continue;
                }
                if (c == quote) {
                    return i;
                }
                i++;
            }
            return n;
        }

        private int closingBracket(final int from, final char open, final char close) {
            int depth = 0;
            for (int i = from; i < n; i++) {
                char c = s.charAt(i);
                if (c == open) {
                    depth++;
                } else if (c == close && --depth == 0) {
                    return i + 1;
                }
            }
            return n;
        }

        /**
         * Конец значения без кавычек: до запятой, точки с запятой, скобки, кавычки, перевода строки или «<».
         * Пробелы входят в значение (ФИО), пока следующее слово не окажется новым ключом «слово=» или «слово:».
         */
        private int bareEnd(final int from) {
            int i = from;
            while (i < n) {
                char c = s.charAt(i);
                if (c == ',' || c == ';' || c == '&' || c == ')' || c == ']' || c == '}' || c == '"' || c == '\''
                    || c == '\n' || c == '\r' || c == '<' || c == '\t') {
                    return i;
                }
                if (c == ' ') {
                    int word = i + 1;
                    int k = word;
                    while (k < n && isKeyChar(s.charAt(k))) {
                        k++;
                    }
                    if (k == word || (k < n && (s.charAt(k) == '=' || s.charAt(k) == ':'))) {
                        return i;
                    }
                }
                i++;
            }
            return n;
        }

        // ---------- XML: <cvv>123</cvv> и &lt;cvv&gt;123&lt;/cvv&gt; ----------

        private int xmlValue(final int close, final int valueStart) {
            boolean escaped = valueStart - close == 4;
            // открывающий «<» ищем только рядом, чтобы текст с множеством «>» не давал квадратичного времени
            int open = -1;
            for (int k = close - 1; k >= 0 && close - k <= MAX_KEY + 4; k--) {
                if (escaped ? s.startsWith("&lt;", k) : s.charAt(k) == '<') {
                    open = k;
                    break;
                }
            }
            int nameStart = open + (escaped ? 4 : 1);
            if (open < 0 || close - nameStart <= 0 || s.charAt(nameStart) == '/') {
                return valueStart;
            }
            int nameEnd = nameStart;
            while (nameEnd < close && (isKeyChar(s.charAt(nameEnd)) || s.charAt(nameEnd) == ':')) {
                nameEnd++;
            }
            String name = s.substring(nameStart, nameEnd);
            int colon = name.lastIndexOf(':');
            Rule rule = rules.ruleFor(colon >= 0 ? name.substring(colon + 1) : name);
            if (rule == null || s.charAt(close - 1) == '/') {
                return valueStart;
            }
            int end = escaped ? s.indexOf("&lt;", valueStart) : s.indexOf('<', valueStart);
            end = end < 0 ? n : end;
            apply(rule, valueStart, end, true);
            return end;
        }

        // ---------- номера карт и телефоны ----------

        /**
         * Числа в тексте. Цифры собираются в группы через пробел, дефис или скобки ({@code 8600 1234 5678 1233},
         * {@code +998 (90) 123-45-67}). Номером карты считается любая непрерывная последовательность групп
         * из 13–19 цифр с верной контрольной суммой, телефоном — из 12 цифр, начинающаяся с 998. После номера
         * разбор продолжается со следующей группы, поэтому номер не теряется, если за ним идут другие числа.
         */
        private int digits(final int start) {
            if (start > 0 && isWordChar(s.charAt(start - 1))) {
                return skipWord(start);
            }
            boolean plus = s.charAt(start) == '+';
            int[] groupStart = new int[MAX_GROUPS];
            int[] groupEnd = new int[MAX_GROUPS];
            int groups = 0;
            int i = plus ? start + 1 : start;
            while (i < n && groups < MAX_GROUPS) {
                int g = i;
                while (i < n && isDigit(s.charAt(i))) {
                    i++;
                }
                if (i == g) {
                    break;
                }
                if (i < n && isWordChar(s.charAt(i))) {
                    // число вплотную к букве — часть идентификатора (abc123, 123abc), не номер
                    if (groups == 0) {
                        return skipWord(i);
                    }
                    break;
                }
                groupStart[groups] = g;
                groupEnd[groups] = i;
                groups++;
                int sep = i;
                while (sep < n && sep - i < 3 && isNumberSeparator(s.charAt(sep))) {
                    sep++;
                }
                if (sep == i || sep >= n || !isDigit(s.charAt(sep))) {
                    break;
                }
                i = sep;
            }
            if (groups == 0) {
                return start + 1;
            }
            int first = 0;
            while (first < groups) {
                int matched = match(groupStart, groupEnd, first, groups, plus && first == 0 ? start : -1);
                first = matched > first ? matched : first + 1;
            }
            return groupEnd[groups - 1];
        }

        /**
         * Ищет номер карты или телефон, начинающийся с группы {@code first}; маскирует его и возвращает индекс
         * группы после него, или {@code first}, если ничего не найдено.
         */
        private int match(final int[] from, final int[] to, final int first, final int groups, final int plusAt) {
            int digits = 0;
            for (int last = first; last < groups; last++) {
                digits += to[last] - from[last];
                if (digits > PAN_MAX) {
                    break;
                }
                if (digits >= PAN_MIN && luhn(s, from[first], to[last])) {
                    replace(from[first], to[last], maskDigits(s.substring(from[first], to[last]), PAN_HEAD, TAIL));
                    return last + 1;
                }
                if (digits == PHONE_DIGITS && s.startsWith("998", from[first])) {
                    replace(from[first], to[last], maskDigits(s.substring(from[first], to[last]), 0, TAIL));
                    return last + 1;
                }
            }
            return first;
        }

        private int skipWord(final int from) {
            int i = from;
            while (i < n && (isWordChar(s.charAt(i)) || isDigit(s.charAt(i)))) {
                i++;
            }
            return Math.max(i, from + 1);
        }

        // ---------- Bearer ----------

        private int bearer(final int start) {
            int i = start + BEARER.length();
            if ((start > 0 && isLetterOrDigit(s.charAt(start - 1))) || i >= n || !Character.isWhitespace(s.charAt(i))) {
                return start + 1;
            }
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            int end = i;
            while (end < n && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != '"' && s.charAt(end) != ','
                && s.charAt(end) != ';') {
                end++;
            }
            if (end > i) {
                replace(i, end, HIDDEN);
            }
            return end;
        }
    }

    /** Значение поля с известным именем: правило по имени, затем общий разбор текста значения. */
    public static String maskField(final String name, final String value, final MaskingRules rules) {
        if (value == null) {
            return null;
        }
        Rule rule = rules.ruleFor(name);
        return rule == null ? mask(value, rules) : maskValue(rule, value, true, rules, 1);
    }

    /**
     * Путь URL для лога: сегменты из 9 и более цифр (ПИНФЛ, телефон, номер документа) скрываются, остальное
     * маскируется как обычный текст. Шаблон маршрута ({@code http.route}) пишется отдельно и не маскируется.
     */
    public static String maskPath(final String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        return maskOrPlaceholder(maskPathSegments(path));
    }

    static String maskPathSegments(final String path) {
        StringBuilder result = new StringBuilder(path.length());
        int start = 0;
        while (start <= path.length()) {
            int slash = path.indexOf('/', start);
            int end = slash < 0 ? path.length() : slash;
            String segment = path.substring(start, end);
            // номер карты оставляем узнаваемым (первые 6 и последние 4), прочие длинные числа скрываем целиком
            result.append(isPan(segment) ? maskPan(segment) : segment.length() >= 9 && isDigits(segment) ? HIDDEN : segment);
            if (slash < 0) {
                break;
            }
            result.append('/');
            start = slash + 1;
        }
        return result.toString();
    }

    // ---------- маскирование значений, общее для текста и JSON ----------

    /**
     * Значение поля по правилу. Для кодов и адресов, которые остаются в логе, значение дополнительно
     * проходит общий разбор текста: в нём тоже бывают номера карт и телефоны.
     */
    static String maskValue(final Rule rule, final String value, final boolean quoted, final MaskingRules rules) {
        return maskValue(rule, value, quoted, rules, 1);
    }

    static String maskValue(final Rule rule, final String value, final boolean quoted, final MaskingRules rules,
                            final int depth) {
        return switch (rule) {
            case PAN -> maskPan(value);
            case TAIL -> maskTail(value);
            case NAME -> value.isEmpty() ? value : value.substring(0, value.offsetByCodePoints(0, 1)) + HIDDEN;
            case HIDE -> HIDDEN;
            case EMAIL -> maskEmail(value);
            // код подтверждения: 4–8 цифр в любом виде — строкой, числом, без кавычек
            case DIGITS -> isOneTimeCode(value) ? HIDDEN : mask(value, rules, depth);
            // поле code: строка из цифр — OTP, число без кавычек — код ошибки
            case CODE -> quoted && isDigits(value) ? HIDDEN : mask(value, rules, depth);
            case URL -> mask(maskUrl(value), rules, depth);
        };
    }

    static boolean isOneTimeCode(final String value) {
        return value.length() >= 4 && value.length() <= 8 && isDigits(value);
    }

    /** Почта: первая буква и домен: i***@mail.uz. */
    static String maskEmail(final String text) {
        int at = text.indexOf('@');
        return at <= 0 ? HIDDEN : text.substring(0, text.offsetByCodePoints(0, 1)) + HIDDEN + text.substring(at);
    }

    static String maskPan(final String text) {
        String result = HIDDEN;
        if (text.indexOf('*') >= 0 && countDigits(text) <= PAN_HEAD + TAIL) {
            // уже замаскирован на стороне процессинга, например 860051******0111
            result = text;
        } else if (text.indexOf('*') >= 0) {
            result = maskDigits(text, PAN_HEAD, TAIL);
        } else {
            int digits = 0;
            boolean other = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (isDigit(c)) {
                    digits++;
                } else if (!isNumberSeparator(c)) {
                    other = true;
                }
            }
            if (!other && digits >= PAN_MIN && digits <= PAN_MAX) {
                result = maskDigits(text, PAN_HEAD, TAIL);
            }
        }
        return result;
    }

    static String maskTail(final String text) {
        return text.length() <= TAIL ? HIDDEN : HIDDEN + text.substring(text.length() - TAIL);
    }

    /** Адрес без параметров запроса и без учётных данных: https://host/path?*** . */
    static String maskUrl(final String text) {
        String result = text;
        int query = result.indexOf('?');
        if (query >= 0) {
            result = result.substring(0, query + 1) + HIDDEN;
        }
        int scheme = result.indexOf("://");
        int at = scheme < 0 ? -1 : result.indexOf('@', scheme + 3);
        int slash = scheme < 0 ? -1 : result.indexOf('/', scheme + 3);
        if (at > 0 && (slash < 0 || at < slash)) {
            result = result.substring(0, scheme + 3) + result.substring(at + 1);
            slash = result.indexOf('/', scheme + 3);
        }
        int pathStart = scheme < 0 ? (result.startsWith("/") ? 0 : -1) : slash;
        if (pathStart >= 0) {
            int pathEnd = result.indexOf('?', pathStart);
            pathEnd = pathEnd < 0 ? result.length() : pathEnd;
            result = result.substring(0, pathStart) + maskPathSegments(result.substring(pathStart, pathEnd))
                + result.substring(pathEnd);
        }
        return result;
    }

    /** Заменяет на '*' все цифры, кроме первых {@code head} и последних {@code tail}; разделители остаются. */
    static String maskDigits(final String text, final int head, final int tail) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isDigit(text.charAt(i))) {
                total++;
            }
        }
        StringBuilder result = new StringBuilder(text);
        int seen = 0;
        for (int i = 0; i < result.length(); i++) {
            if (isDigit(result.charAt(i))) {
                if (seen >= head && seen < total - tail) {
                    result.setCharAt(i, '*');
                }
                seen++;
            }
        }
        return result.toString();
    }

    /** Строка целиком — номер карты (13–19 цифр, проверка Луна)? Для числовых значений JSON. */
    static boolean isPan(final String digits) {
        return digits.length() >= PAN_MIN && digits.length() <= PAN_MAX && isDigits(digits)
            && luhn(digits, 0, digits.length());
    }

    static boolean isDigits(final String text) {
        boolean digits = !text.isEmpty();
        for (int i = 0; i < text.length() && digits; i++) {
            digits = isDigit(text.charAt(i));
        }
        return digits;
    }

    private static boolean luhn(final CharSequence text, final int from, final int to) {
        int sum = 0;
        boolean doubleIt = false;
        for (int i = to - 1; i >= from; i--) {
            char c = text.charAt(i);
            if (!isDigit(c)) {
                continue;
            }
            int d = c - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    private static boolean isDigit(final char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLetterOrDigit(final char c) {
        return isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || Character.isLetter(c);
    }

    private static int countDigits(final String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isDigit(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isNumberSeparator(final char c) {
        return c == ' ' || c == '-' || c == '(' || c == ')' || c == '.';
    }

    /** Буква латиницы или «_»: число вплотную к ним — идентификатор. Кириллица номер не скрывает (карта8600…). */
    private static boolean isWordChar(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isKeyChar(final char c) {
        return isLetterOrDigit(c) || c == '-' || c == '.';
    }
}
