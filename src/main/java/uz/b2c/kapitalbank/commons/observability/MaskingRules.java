package uz.b2c.kapitalbank.commons.observability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Правила маскирования ПДн: базовые правила банка плюс поля, которые добавила команда.
 * <p>
 * Базовые правила распознают поле по частям имени, а не по точному совпадению: имя разбивается на слова
 * (camelCase, snake_case, kebab-case), поэтому {@code cardNumber}, {@code card_number}, {@code senderCardNumber},
 * {@code CARD_NO} одинаково считаются номером карты, а {@code newPassword}, {@code access_token},
 * {@code clientSecret} — секретами. Команда может только добавить правила: если поле узнали и база, и команда
 * (по имени или по пути), применяется более строгое из правил ({@link Rule#strictness()}), поэтому команда не может
 * ослабить базу, а расширение базы не может ослабить поле, которое команда скрыла. Выключателя маскирования нет.
 * <p>
 * Одни и те же правила используют маскирование тел HTTP ({@link JsonBodyMasker}) и каждой строки лога
 * ({@link LogTextMasker}). До старта Spring-контекста для строк лога действуют только базовые правила,
 * после — базовые плюс поля команды ({@link #install(MaskingRules)}).
 */
public final class MaskingRules {

    /** Как маскируется значение. */
    public enum Rule {
        /** Номер карты: первые 6 и последние 4 цифры. Уже замаскированный номер не трогается. */
        PAN(5),
        /** Телефон, счёт: только последние 4 символа. */
        TAIL(6),
        /** ФИО: только первая буква. */
        NAME(7),
        /** Значение скрывается целиком, включая вложенные объекты. */
        HIDE(8),
        /** Код подтверждения: 4–8 цифр скрываются в любом виде; бизнес-коды вроде CARD_EXPIRED остаются. */
        DIGITS(3),
        /** Поле {@code code}: строка из цифр скрывается (OTP), число и текст остаются (коды ошибок). */
        CODE(2),
        /** Почта: первая буква и домен. */
        EMAIL(4),
        /** Адрес: схема, хост и путь остаются, параметры запроса скрываются. */
        URL(1);

        private final int strictness;

        Rule(final int strictness) {
            this.strictness = strictness;
        }

        /** Строгость: чем больше, тем меньше от значения остаётся в логе. HIDE — самое строгое. */
        public int strictness() {
            return strictness;
        }
    }

    /** Более строгое из двух правил; {@code null} — поле не ПДн. */
    static Rule stricter(final Rule a, final Rule b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.strictness > a.strictness ? b : a;
    }

    /** Слова, при наличии которых в любом месте имени значение скрывается целиком. */
    private static final Set<String> HIDE_ANYWHERE = Set.of(
        "cvv", "cvv2", "cvc", "cvc2", "pinfl", "otp", "password", "passwd", "pwd", "secret", "token", "tokens",
        "passport", "jwt", "credential", "credentials", "cookie", "jshshir", "dob", "birthday",
        "address",
        // заголовок и данные авторизации в любом месте имени: authorizationKey, authorizationValue;
        // деловые поля авторизации платежа открыты явным списком (AUTHORIZATION_BUSINESS_LAST)
        "authorization",
        // код безопасности карты под другими именами (CSC, CVN)
        "csc", "cvn",
        // чувствительные аутентификационные данные карты (PCI DSS): не хранятся ни в каком виде
        "track1", "track2", "pvv", "cavv", "icvv", "pinblock",
        // ПИНФЛ и ИНН (СТИР)
        "pnfl", "stir");

    /** Последнее слово имени, при котором значение скрывается целиком. */
    private static final Set<String> HIDE_LAST = Set.of(
        "pin", "expiry", "expire", "expiration", "smscode", "birthdate", "birth", "inn", "tin", "codeword",
        "cred", "creds", "salt",
        // ИНН: taxPayerCode, taxId
        "taxpayercode", "taxpayerid", "taxpayernumber", "taxid",
        // процессинг (Tieto, Humo): B_DATE — дата рождения, R_STREET и R_PCODE — улица и индекс адреса проживания,
        // SERIAL_NO — серия документа клиента (только точное имя: terminalSerialNo и подобные остаются),
        // cardSecurityCode — CVV2
        "bdate", "street", "rpcode", "serialno", "cardsecuritycode");

    /** Пары слов в конце имени, при которых значение скрывается целиком. */
    private static final Set<String> HIDE_LAST_PAIR = Set.of(
        "pin code", "sms code", "birth date", "date birth", "series number", "valid to", "exp date", "expiry date",
        "secret key", "api key", "private key", "card expiry", "valid thru", "doc number", "document number",
        "doc no", "document no", "doc num", "document num",
        "expiration date", "expire date", "exp month", "exp year", "expiry month", "expiry year",
        "track data", "pin block", "national id", "personal number", "doc series", "document series",
        "doc serial", "document serial", "code word", "expiration month", "expiration year",
        "access key", "authorization header", "auth header", "birth place", "place birth",
        // код безопасности карты: всегда секрет, в том числе из 3 цифр
        "security code", "secure code", "verification value",
        // процессинг: PERSON_CODE — ПИНФЛ, ID_CARD — удостоверение личности
        "person code", "id card");

    /**
     * Последнее слово, при котором поле со словом authorization — деловое, а не секрет: authorizationDateTime,
     * authorizationStatus, authorizationId. {@code authorizationCode} сюда не входит, пока не подтверждено, что это
     * код одобрения процессинга (ISO 8583, поле 38), а не OAuth-код.
     */
    private static final Set<String> AUTHORIZATION_BUSINESS_LAST = Set.of(
        "date", "time", "datetime", "timestamp", "id", "status", "state", "service", "helper", "path", "type", "mode",
        "required", "enabled", "method", "result");

    /** Слова документа и детали, при которых поле документа скрывается: documentSeria, docIssueDate, docGivePlace. */
    private static final Set<String> DOC_WORDS = Set.of("doc", "docs", "document", "documents", "identity");
    private static final Set<String> DOC_DETAIL_LAST = Set.of("number", "no", "num", "seria", "series", "serial", "place");
    private static final Set<String> DOC_DETAIL_ANYWHERE = Set.of("issue", "issued", "give", "given", "issuer");

    /** Суффиксы языка и алфавита: firstNameLatin, surnameCyr, lastNameEn — то же поле на другом языке. */
    private static final Set<String> LANGUAGE_SUFFIXES = Set.of(
        "en", "eng", "english", "ru", "rus", "uz", "uzb", "lat", "latin", "cyr", "cyrl", "cyrillic", "local", "native");

    private static final Set<String> PAN_LAST = Set.of("pan", "pan2", "hpan", "crefno", "cardnumber", "cardno",
        // processingKey — номер карты в путях и телах обмена с процессингом (recipient-info/{processingKey})
        "processingkey",
        // primaryAccountNumber (ISO 8583) — номер карты, а не счёт
        "primaryaccountnumber");
    private static final Set<String> PAN_LAST_PAIR = Set.of(
        "card number", "card no", "card num", "cref no", "processing key");

    private static final Set<String> TAIL_LAST = Set.of(
        "phone", "phones", "telefon", "msisdn", "account", "accno", "aacct", "acct", "iban", "mobile", "tel",
        "telephone");
    private static final Set<String> TAIL_LAST_PAIR = Set.of(
        "phone number", "phone no", "account number", "account no", "acc no", "acc number", "mobile number",
        "mobile no", "tel number", "tel no");
    private static final Set<String> TAIL_ANYWHERE = Set.of("phone", "msisdn");

    private static final Set<String> NAME_QUALIFIERS = Set.of(
        "first", "last", "middle", "full", "holder", "embos", "client", "customer", "sender", "receiver",
        "recipient", "owner", "cardholder", "given", "family", "sur", "father", "patronymic", "embossed",
        "beneficiary", "payer", "payee",
        "second", "patronym",
        // процессинг: ABVR_NAME — сокращённое имя клиента, CARD_NAME — имя на карте (эмбоссирование)
        "abvr", "card");
    private static final Set<String> NAME_LAST = Set.of(
        "surname", "fio", "patronymic", "fullname", "holder", "cardholder", "firstname", "lastname", "middlename",
        "givenname", "familyname", "nameoncard",
        "patronym",
        // процессинг: F_NAMES — имена клиента, M_NAME — отчество
        "fnames", "mname");

    /** Объекты, у которых поле {@code name} — имя человека: {"client":{"name":"…"}}. */
    static final Set<String> PERSON_OBJECTS = Set.of(
        "client", "customer", "card", "holder", "cardholder", "owner", "sender", "receiver", "recipient",
        "beneficiary", "payer", "payee", "user", "person", "profile");

    /** Коды подтверждения: значение-строка из цифр скрывается, «CARD_EXPIRED» и числовые коды ошибок остаются. */
    private static final Set<String> DIGITS_PAIR = Set.of(
        "confirm code", "confirmation code", "verification code", "verify code", "activation code",
        "auth code", "check code");
    private static final Set<String> URL_LAST = Set.of("url", "uri", "link", "href");

    private static final int CACHE_LIMIT = 10_000;
    /** Длиннее — не кэшируется: имена полей короткие, а длинный ключ — либо мусор, либо атака на память. */
    private static final int CACHE_KEY_MAX = 64;
    /** С таким числом цифр имя не кэшируется: ключи-номера телефонов и карт — ПДн, им не место в хипе навсегда. */
    private static final int CACHE_KEY_MAX_DIGITS = 4;

    private static volatile MaskingRules current = new MaskingRules(Map.of(), List.of());

    private final Map<String, Rule> teamNames;
    private final List<PathRule> teamPaths;
    private final Map<String, Optional<Rule>> cache = new ConcurrentHashMap<>();

    record PathRule(String[] segments, Rule rule) {
    }

    private MaskingRules(final Map<String, Rule> teamNames, final List<PathRule> teamPaths) {
        this.teamNames = Map.copyOf(teamNames);
        this.teamPaths = List.copyOf(teamPaths);
    }

    /** Только базовые правила банка. */
    public static MaskingRules base() {
        return new MaskingRules(Map.of(), List.of());
    }

    /** Базовые правила плюс поля команды из конфигурации. */
    public static MaskingRules of(final HttpLogProperties.Masking masking) {
        Map<String, Rule> names = new HashMap<>();
        List<PathRule> paths = new ArrayList<>();
        // при совпадении имени в двух группах команды побеждает более строгая (порядок — по возрастанию строгости)
        register(masking.getCodes(), Rule.DIGITS, names, paths);
        register(masking.getName(), Rule.NAME, names, paths);
        register(masking.getPhone(), Rule.TAIL, names, paths);
        register(masking.getAccount(), Rule.TAIL, names, paths);
        register(masking.getPan(), Rule.PAN, names, paths);
        register(masking.getHide(), Rule.HIDE, names, paths);
        return new MaskingRules(names, paths);
    }

    /** Правила, которые сейчас применяются к строкам лога. */
    public static MaskingRules current() {
        return current;
    }

    /** Подменяет правила для строк лога: вызывается библиотекой после чтения конфигурации сервиса. */
    public static void install(final MaskingRules rules) {
        current = rules;
    }

    /**
     * Правило для поля с данным именем или {@code null}, если поле не ПДн.
     */
    public Rule ruleFor(final String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Optional<Rule> cached = cache.get(name);
        if (cached != null) {
            return cached.orElse(null);
        }
        Rule rule = compute(name);
        if (cache.size() < CACHE_LIMIT && cacheable(name)) {
            cache.put(name, Optional.ofNullable(rule));
        }
        return rule;
    }

    /**
     * Правило без кэша — для значений из тела, которые называют поле ({@code {"name":"password","value":…}}):
     * в них бывают ФИО и произвольный текст, и кэшировать их нельзя.
     */
    public Rule ruleForUncached(final String name) {
        if (name == null || name.isEmpty() || name.length() > CACHE_KEY_MAX) {
            return null;
        }
        Optional<Rule> cached = cache.get(name);
        return cached != null ? cached.orElse(null) : compute(name);
    }

    /**
     * Правило базы и правило команды по имени — более строгое из двух. Варианты имени без суффикса языка и без номера
     * на конце проверяются для обоих: {@code loyaltyId2} в поле команды {@code loyaltyId} — то же поле.
     */
    private Rule compute(final String name) {
        List<String> words = words(name);
        if (words.isEmpty()) {
            return null;
        }
        Rule base = baseRule(words);
        Rule team = teamNames.isEmpty() ? null : teamRule(words);
        return stricter(base, team);
    }

    private Rule teamRule(final List<String> words) {
        Rule rule = teamNames.get(String.join("", words));
        if (rule == null && hasVariants(words)) {
            for (List<String> variant : variants(words)) {
                rule = teamNames.get(String.join("", variant));
                if (rule != null) {
                    break;
                }
            }
        }
        return rule;
    }

    private static boolean cacheable(final String name) {
        if (name.length() > CACHE_KEY_MAX) {
            return false;
        }
        int digits = 0;
        for (int i = 0; i < name.length(); i++) {
            if (Character.isDigit(name.charAt(i)) && ++digits >= CACHE_KEY_MAX_DIGITS) {
                return false;
            }
        }
        return true;
    }

    List<PathRule> pathRules() {
        return teamPaths;
    }

    /**
     * Базовое правило банка по словам имени. Если по словам как есть правила нет, имя проверяется ещё раз без
     * суффикса языка ({@code firstNameLatin} → first name) и без номеров на конце ({@code EXP_DATE1}, {@code address2},
     * {@code pin_2}, {@code PIN_EN_1}): это то же поле.
     */
    static Rule baseRule(final String name) {
        return baseRule(words(name));
    }

    private static Rule baseRule(final List<String> words) {
        if (words.isEmpty()) {
            return null;
        }
        Rule rule = ruleForWords(words);
        // варианты перебираются только у имён с суффиксом языка или цифрой на конце слова: у остальных их нет
        if (rule == null && hasVariants(words)) {
            for (List<String> variant : variants(words)) {
                rule = ruleForWords(variant);
                if (rule != null) {
                    break;
                }
            }
        }
        return rule;
    }

    /** Есть ли у имени варианты: последнее слово — суффикс языка или какое-то слово кончается цифрой. */
    private static boolean hasVariants(final List<String> words) {
        if (words.size() > 1 && LANGUAGE_SUFFIXES.contains(words.get(words.size() - 1))) {
            return true;
        }
        for (String word : words) {
            if (Character.isDigit(word.charAt(word.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /** Без суффикса языка; без номеров на концах слов; без того и другого — в этом порядке, без повторов. */
    private static List<List<String>> variants(final List<String> words) {
        List<String> withoutLanguage = withoutLanguage(words);
        List<String> withoutDigits = withoutTrailingDigits(words);
        List<String> withoutBoth = withoutLanguage(withoutDigits);
        List<List<String>> result = new ArrayList<>(3);
        for (List<String> variant : List.of(withoutLanguage, withoutDigits, withoutBoth)) {
            if (!variant.isEmpty() && !variant.equals(words) && !result.contains(variant)) {
                result.add(variant);
            }
        }
        return result;
    }

    private static Rule ruleForWords(final List<String> words) {
        if (words.isEmpty()) {
            return null;
        }
        String last = words.get(words.size() - 1);
        String pair = words.size() > 1 ? words.get(words.size() - 2) + " " + last : null;
        String joined = words.size() == 1 ? last : String.join("", words);
        Rule rule = null;
        if (anyIn(words, HIDE_ANYWHERE) && !businessAuthorization(words, last) || HIDE_LAST.contains(last)
            || HIDE_LAST.contains(joined) || (pair != null && HIDE_LAST_PAIR.contains(pair)) || documentDetail(words, last)) {
            rule = Rule.HIDE;
        } else if (PAN_LAST.contains(last) || PAN_LAST.contains(joined)
            || (pair != null && PAN_LAST_PAIR.contains(pair))) {
            rule = Rule.PAN;
        } else if (TAIL_LAST.contains(last) || TAIL_LAST.contains(joined)
            || (pair != null && TAIL_LAST_PAIR.contains(pair)) || anyIn(words, TAIL_ANYWHERE)) {
            rule = Rule.TAIL;
        } else if (NAME_LAST.contains(last) || NAME_LAST.contains(joined)
            || ("name".equals(last) && words.size() > 1 && NAME_QUALIFIERS.contains(words.get(words.size() - 2)))) {
            rule = Rule.NAME;
        } else if ("email".equals(last) || "mail".equals(last) || "mails".equals(last) || words.contains("email")
            || joined.endsWith("email") || joined.endsWith("emails")) {
            rule = Rule.EMAIL;
        } else if (URL_LAST.contains(last)) {
            rule = Rule.URL;
        } else if (pair != null && DIGITS_PAIR.contains(pair)) {
            rule = Rule.DIGITS;
        } else if (words.size() == 1 && "code".equals(last)) {
            rule = Rule.CODE;
        }
        return rule;
    }

    private static boolean anyIn(final List<String> words, final Set<String> set) {
        for (String word : words) {
            if (set.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Деловое поле авторизации платежа (authorizationDateTime, authorizationStatus): слово authorization — единственное
     * секретное слово в имени, и оно не последнее, а последнее слово — из явного списка.
     */
    private static boolean businessAuthorization(final List<String> words, final String last) {
        if (!AUTHORIZATION_BUSINESS_LAST.contains(last) || !words.contains("authorization")) {
            return false;
        }
        for (String word : words) {
            if (!"authorization".equals(word) && HIDE_ANYWHERE.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Поле документа: серия, номер, место и дата выдачи (documentSeria, docIssueDate, docGivePlace). */
    private static boolean documentDetail(final List<String> words, final String last) {
        return words.size() > 1 && anyIn(words, DOC_WORDS)
            && (DOC_DETAIL_LAST.contains(last) || anyIn(words, DOC_DETAIL_ANYWHERE));
    }

    private static List<String> withoutLanguage(final List<String> words) {
        int end = words.size();
        while (end > 1 && LANGUAGE_SUFFIXES.contains(words.get(end - 1))) {
            end--;
        }
        return end == words.size() ? words : words.subList(0, end);
    }

    /**
     * Без номеров на концах слов: {@code address2} → address, {@code en1} → en; слово из одних цифр в конце имени
     * отбрасывается ({@code pin_2} → pin). Слово, от которого без цифр осталась одна буква ({@code p1}), — как есть.
     */
    private static List<String> withoutTrailingDigits(final List<String> words) {
        int size = words.size();
        while (size > 1 && isDigits(words.get(size - 1))) {
            size--;
        }
        List<String> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(withoutTrailingDigits(words.get(i)));
        }
        return result;
    }

    private static String withoutTrailingDigits(final String word) {
        int end = word.length();
        while (end > 0 && Character.isDigit(word.charAt(end - 1))) {
            end--;
        }
        return end >= 2 ? word.substring(0, end) : word;
    }

    private static boolean isDigits(final String word) {
        for (int i = 0; i < word.length(); i++) {
            if (!Character.isDigit(word.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Разбивает имя поля на слова в нижнем регистре: {@code senderCardNumber} → [sender, card, number],
     * {@code CARD_NO} → [card, no], {@code holder-full-name} → [holder, full, name]. Цифры остаются в слове: cvv2.
     */
    static List<String> words(final String name) {
        List<String> words = new ArrayList<>(4);
        StringBuilder word = new StringBuilder();
        int length = name.length();
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                flush(word, words);
                continue;
            }
            if (Character.isUpperCase(c) && word.length() > 0) {
                char prev = name.charAt(i - 1);
                boolean nextLower = i + 1 < length && Character.isLowerCase(name.charAt(i + 1));
                // aB → a|B; ABc → A|Bc (CREFNo → CREF|No)
                if (Character.isLowerCase(prev) || Character.isDigit(prev) || (Character.isUpperCase(prev) && nextLower)) {
                    flush(word, words);
                }
            }
            word.append(Character.toLowerCase(c));
        }
        flush(word, words);
        return words;
    }

    private static void flush(final StringBuilder word, final List<String> words) {
        if (word.length() > 0) {
            words.add(word.toString());
            word.setLength(0);
        }
    }

    static String normalize(final String name) {
        return String.join("", words(name));
    }

    private static void register(final Collection<String> source, final Rule rule,
                                 final Map<String, Rule> names, final List<PathRule> paths) {
        if (source == null) {
            return;
        }
        for (String raw : source) {
            String key = raw == null ? "" : raw.trim();
            if (key.contains(".")) {
                String[] segments = key.split("\\.");
                for (int i = 0; i < segments.length; i++) {
                    segments[i] = normalize(segments[i]);
                }
                paths.add(new PathRule(segments, rule));
            } else if (!key.isEmpty()) {
                names.put(normalize(key), rule);
            }
        }
    }

    static String lower(final String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
