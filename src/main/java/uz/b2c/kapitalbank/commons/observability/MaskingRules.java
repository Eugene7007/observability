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
 * {@code clientSecret} — секретами. Базовые правила проверяются первыми, и команда не может их ни убрать,
 * ни ослабить: её поля применяются только к именам, которые база не узнала. Выключателя маскирования нет.
 * <p>
 * Одни и те же правила используют маскирование тел HTTP ({@link JsonBodyMasker}) и каждой строки лога
 * ({@link LogTextMasker}). До старта Spring-контекста для строк лога действуют только базовые правила,
 * после — базовые плюс поля команды ({@link #install(MaskingRules)}).
 */
public final class MaskingRules {

    /** Как маскируется значение. */
    public enum Rule {
        /** Номер карты: первые 6 и последние 4 цифры. Уже замаскированный номер не трогается. */
        PAN,
        /** Телефон, счёт: только последние 4 символа. */
        TAIL,
        /** ФИО: только первая буква. */
        NAME,
        /** Значение скрывается целиком, включая вложенные объекты. */
        HIDE,
        /** Код подтверждения: 4–8 цифр скрываются в любом виде; бизнес-коды вроде CARD_EXPIRED остаются. */
        DIGITS,
        /** Поле {@code code}: строка из цифр скрывается (OTP), число и текст остаются (коды ошибок). */
        CODE,
        /** Почта: первая буква и домен. */
        EMAIL,
        /** Адрес: схема, хост и путь остаются, параметры запроса скрываются. */
        URL
    }

    /** Слова, при наличии которых в любом месте имени значение скрывается целиком. */
    private static final Set<String> HIDE_ANYWHERE = Set.of(
        "cvv", "cvv2", "cvc", "cvc2", "pinfl", "otp", "password", "passwd", "pwd", "secret", "token", "tokens",
        "passport", "jwt", "credential", "credentials", "authorization", "cookie", "jshshir", "dob", "birthday",
        "address");

    /** Последнее слово имени, при котором значение скрывается целиком. */
    private static final Set<String> HIDE_LAST = Set.of("pin", "expiry", "smscode", "birthdate", "birth");

    /** Пары слов в конце имени, при которых значение скрывается целиком. */
    private static final Set<String> HIDE_LAST_PAIR = Set.of(
        "pin code", "sms code", "birth date", "date birth", "series number", "valid to", "exp date", "expiry date",
        "secret key", "api key", "private key", "card expiry", "valid thru", "doc number", "document number",
        "doc no", "document no", "doc num", "document num");

    private static final Set<String> PAN_LAST = Set.of("pan", "pan2", "hpan", "crefno", "cardnumber", "cardno", "processingKey");
    private static final Set<String> PAN_LAST_PAIR = Set.of("card number", "card no", "card num", "cref no");

    private static final Set<String> TAIL_LAST = Set.of("phone", "msisdn", "account", "accno", "aacct", "iban");
    private static final Set<String> TAIL_LAST_PAIR = Set.of(
        "phone number", "phone no", "account number", "account no", "acc no", "acc number");
    private static final Set<String> TAIL_ANYWHERE = Set.of("phone", "msisdn");

    private static final Set<String> NAME_QUALIFIERS = Set.of(
        "first", "last", "middle", "full", "holder", "embos", "client", "customer", "sender", "receiver",
        "recipient", "owner", "cardholder", "given", "family", "sur", "father", "patronymic");
    private static final Set<String> NAME_LAST = Set.of("surname", "fio", "patronymic", "fullname", "holder", "cardholder");

    /** Объекты, у которых поле {@code name} — имя человека: {"client":{"name":"…"}}. */
    static final Set<String> PERSON_OBJECTS = Set.of(
        "client", "customer", "card", "holder", "cardholder", "owner", "sender", "receiver", "recipient",
        "beneficiary", "payer", "payee", "user", "person", "profile");

    /** Коды подтверждения: значение-строка из цифр скрывается, «CARD_EXPIRED» и числовые коды ошибок остаются. */
    private static final Set<String> DIGITS_PAIR = Set.of(
        "confirm code", "confirmation code", "verification code", "verify code", "activation code",
        "security code", "auth code", "check code");
    private static final Set<String> URL_LAST = Set.of("url", "uri", "link", "href");

    private static final int CACHE_LIMIT = 50_000;

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
        Rule rule = baseRule(name);
        if (rule == null) {
            rule = teamNames.get(normalize(name));
        }
        if (cache.size() < CACHE_LIMIT) {
            cache.put(name, Optional.ofNullable(rule));
        }
        return rule;
    }

    List<PathRule> pathRules() {
        return teamPaths;
    }

    /** Базовое правило банка по словам имени. */
    static Rule baseRule(final String name) {
        List<String> words = words(name);
        if (words.isEmpty()) {
            return null;
        }
        String last = words.get(words.size() - 1);
        String pair = words.size() > 1 ? words.get(words.size() - 2) + " " + last : null;
        String joined = String.join("", words);
        Rule rule = null;
        if (words.stream().anyMatch(HIDE_ANYWHERE::contains) || HIDE_LAST.contains(last)
            || HIDE_LAST.contains(joined) || (pair != null && HIDE_LAST_PAIR.contains(pair))) {
            rule = Rule.HIDE;
        } else if (PAN_LAST.contains(last) || PAN_LAST.contains(joined)
            || (pair != null && PAN_LAST_PAIR.contains(pair))) {
            rule = Rule.PAN;
        } else if (TAIL_LAST.contains(last) || TAIL_LAST.contains(joined)
            || (pair != null && TAIL_LAST_PAIR.contains(pair)) || words.stream().anyMatch(TAIL_ANYWHERE::contains)) {
            rule = Rule.TAIL;
        } else if (NAME_LAST.contains(last) || NAME_LAST.contains(joined)
            || ("name".equals(last) && words.size() > 1 && NAME_QUALIFIERS.contains(words.get(words.size() - 2)))) {
            rule = Rule.NAME;
        } else if ("email".equals(last) || "mail".equals(last) || words.contains("email")) {
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
