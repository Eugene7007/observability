package uz.b2c.kapitalbank.commons.observability;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.apache.logging.log4j.LogManager;
import org.springframework.util.AntPathMatcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Скомпилированные настройки: какие пути логировать, для каких писать тела, какие заголовки разрешены,
 * и как превращать захваченное тело в строку для лога.
 */
public final class HttpLogSettings {

    private static final int CLIENT_ERROR = 400;
    private static final JsonFactory JSON = new JsonFactory();
    static final String REQUEST_HEADER_PREFIX = "http.request.header.";
    private static final Set<String> ERROR_CODE_FIELDS = Set.of("code", "errorCode", "error_code");
    private static final int ERROR_CODE_MAX = 64;
    /** Код ищется в начале тела: дальше разбор не идёт, чтобы большое тело без кода не разбиралось целиком. */
    private static final int ERROR_CODE_SCAN_CHARS = 8192;
    static final String REDACTED = "REDACTED";
    private static final Set<String> KNOWN_METHODS = Set.of(
        "GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "OPTIONS", "TRACE", "PATCH", "QUERY");

    private static final Set<String> SECRET_HEADERS = Set.of(
        "authorization", "proxy-authorization", "cookie", "set-cookie", "x-auth-token", "userpinfl", "userphone");

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final int maxBodyBytes;
    private final List<String> excludePaths;
    private final List<String> bodyPaths;
    private final Set<String> allowedHeaders;
    private final JsonBodyMasker masker;
    private final XmlBodyMasker xmlmasker;
    private final HttpLogProperties.Bodies bodies;

    public HttpLogSettings(final HttpLogProperties properties) {
        this.maxBodyBytes = boundedBodyBytes(properties.getMaxBodyBytes());
        this.excludePaths = List.copyOf(properties.getExcludePaths());
        this.bodyPaths = List.copyOf(properties.getBodyPaths());
        this.allowedHeaders = properties.getAllowedHeaders().stream()
            .map(header -> header.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
        this.masker = new JsonBodyMasker(properties.getMasking());
        this.xmlmasker = new XmlBodyMasker(properties.getMasking());
        this.bodies = properties.getBodies();
    }

    static int boundedBodyBytes(final int requested) {
        int bounded = Math.max(HttpLogProperties.MIN_BODY_BYTES, Math.min(HttpLogProperties.MAX_BODY_BYTES, requested));
        if (bounded != requested) {
            LogManager.getLogger("lib.observability").warn("lib-observability: max-body-bytes=" + requested
                + " вне допустимых границ " + HttpLogProperties.MIN_BODY_BYTES + ".." + HttpLogProperties.MAX_BODY_BYTES
                + ", используется " + bounded);
        }
        return bounded;
    }

    /**
     * Писать ли тела в эту запись. У ошибок (4xx, 5xx, исключение) — всегда, это не настраивается.
     * У успешных обменов — только при {@code bodies=ALWAYS} и пути из body-paths.
     */
    boolean includeBodies(final Integer status, final boolean failed, final String path) {
        return failed || (status != null && status >= CLIENT_ERROR) || successBodies(path);
    }

    /** Тела успешных обменов для этого пути: {@code bodies=ALWAYS} и путь из body-paths. */
    boolean successBodies(final String path) {
        return bodies == HttpLogProperties.Bodies.ALWAYS && path != null && matchesAny(bodyPaths, path);
    }

    int maxBodyBytes() {
        return maxBodyBytes;
    }

    boolean isExcluded(final String path) {
        return path != null && matchesAny(excludePaths, path);
    }

    private boolean matchesAny(final List<String> patterns, final String path) {
        for (String pattern : patterns) {
            if (matcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Тело для лога: замаскированный JSON или XML, либо только тип и размер для остальных типов.
     */
    String renderBody(final BodyCapture capture, final String contentType) {
        String rendered = null;
        if (capture != null && capture.total() > 0) {
            rendered = render(capture.buffer(), capture.size(), capture.truncated(), capture.total(), contentType);
        }
        return rendered;
    }

    String renderBody(final byte[] body, final String contentType) {
        String rendered = null;
        if (body != null && body.length > 0) {
            int length = Math.min(body.length, maxBodyBytes);
            rendered = render(body, length, body.length > length, body.length, contentType);
        }
        return rendered;
    }

    private String render(final byte[] data, final int length, final boolean truncated, final long total,
                          final String contentType) {
        String rendered;
        if (isXml(contentType)) {
            rendered = xmlmasker.mask(data, length, truncated, charsetOf(contentType));
        } else if (isJsonOrUnknown(contentType)) {
            rendered = masker.mask(data, length, truncated);
        } else if (contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            String charset = charsetOf(contentType);
            java.nio.charset.Charset decoding = charset != null && java.nio.charset.Charset.isSupported(charset)
                ? java.nio.charset.Charset.forName(charset) : java.nio.charset.StandardCharsets.UTF_8;
            // обрезанная форма: последняя пара может быть оборвана, её значение в лог не идёт
            int usable = length;
            if (truncated) {
                while (usable > 0 && data[usable - 1] != '&') {
                    usable--;
                }
            }
            rendered = renderForm(parseForm(data, usable, decoding));
            rendered = rendered == null ? "[" + contentType + ", " + total + " bytes]"
                : truncated ? rendered + LogTextMasker.TRUNCATED_MARK : rendered;
        } else if (isText(contentType)) {
            rendered = maskText(data, length, truncated, charsetOf(contentType));
        } else {
            rendered = "[" + contentType + ", " + total + " bytes]";
        }
        return rendered;
    }

    /** Разбирает тело формы {@code a=1&b=2}; %XX и '+' декодируются в заданной кодировке. */
    static Map<String, String[]> parseForm(final byte[] data, final int length, final java.nio.charset.Charset charset) {
        Map<String, String[]> result = new java.util.LinkedHashMap<>();
        String text = new String(data, 0, Math.min(length, data.length), java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = decodeFormPart(eq < 0 ? pair : pair.substring(0, eq), charset);
            String value = eq < 0 ? "" : decodeFormPart(pair.substring(eq + 1), charset);
            result.merge(name, new String[] {value}, (a, b) -> {
                String[] joined = java.util.Arrays.copyOf(a, a.length + 1);
                joined[a.length] = b[0];
                return joined;
            });
        }
        return result;
    }

    private static String decodeFormPart(final String raw, final java.nio.charset.Charset charset) {
        String text = new String(raw.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), charset);
        try {
            return java.net.URLDecoder.decode(text, charset);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }

    /**
     * Параметры формы POST как замаскированный JSON: правила применяются по именам параметров, как к полям JSON.
     */
    String renderForm(final Map<String, String[]> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator generator = JSON.createGenerator(out)) {
            generator.writeStartObject();
            for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
                generator.flush();
                if (out.size() > maxBodyBytes) {
                    // дальше в лог всё равно не попадёт: не строим JSON из тысяч параметров
                    break;
                }
                generator.writeFieldName(entry.getKey());
                String[] values = entry.getValue();
                if (values != null && values.length == 1) {
                    generator.writeString(values[0]);
                } else {
                    generator.writeStartArray();
                    for (String value : values == null ? new String[0] : values) {
                        generator.writeString(value);
                    }
                    generator.writeEndArray();
                }
            }
            generator.writeEndObject();
        } catch (IOException e) {
            return "[form, unrenderable]";
        }
        byte[] json = out.toByteArray();
        int length = Math.min(json.length, maxBodyBytes);
        return masker.mask(json, length, json.length > length);
    }

    /**
     * Прежний формат заголовков для Kafka-топика (rc1/rc2): {@code Имя=значение; Имя=значение}, первое значение.
     */
    <T> String renderHeaders(final Iterable<String> names, final Function<String, T> valueOf) {
        StringBuilder result = new StringBuilder();
        for (String name : names) {
            if (name != null && allowedHeaders.contains(name.toLowerCase(Locale.ROOT)) && !isSecretHeader(name)) {
                if (!result.isEmpty()) {
                    result.append("; ");
                }
                result.append(name).append('=').append(LogTextMasker.maskOrPlaceholder(String.valueOf(valueOf.apply(name))));
            }
        }
        return result.isEmpty() ? null : result.toString();
    }

    /**
     * Разрешённые заголовки запроса — по полю на заголовок, как в OpenTelemetry: {@code http.request.header.<имя>},
     * имя в нижнем регистре, значение — массив строк (у заголовка бывает несколько значений). Значения маскируются.
     */
    void addRequestHeaders(final HttpLogMessage message, final Iterable<String> names,
                           final Function<String, ? extends Iterable<String>> valuesOf) {
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (allowedHeaders.contains(lower) && !isSecretHeader(name)) {
                List<String> values = new ArrayList<>(1);
                Iterable<String> raw = valuesOf.apply(name);
                if (raw != null) {
                    for (String value : raw) {
                        values.add(LogTextMasker.maskOrPlaceholder(String.valueOf(value)));
                    }
                }
                if (!values.isEmpty()) {
                    message.field(REQUEST_HEADER_PREFIX + lower, List.copyOf(values));
                }
            }
        }
    }

    /**
     * Бизнес-код ошибки из уже замаскированного JSON-тела ответа: верхнеуровневое {@code code}, {@code errorCode},
     * {@code error_code} или {@code error.code}. Скрытое маскированием значение и длинный текст кодом не считаются.
     */
    static String errorCode(final String maskedJson) {
        if (maskedJson == null || maskedJson.isEmpty() || maskedJson.charAt(0) != '{') {
            return null;
        }
        try (com.fasterxml.jackson.core.JsonParser parser = JSON.createParser(maskedJson)) {
            parser.nextToken();
            String found = null;
            while (found == null && parser.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                com.fasterxml.jackson.core.JsonToken value = parser.nextToken();
                if (parser.currentLocation().getCharOffset() > ERROR_CODE_SCAN_CHARS) {
                    break;
                }
                if (ERROR_CODE_FIELDS.contains(name) && isCodeToken(value)) {
                    found = codeValue(parser.getText());
                } else if ("error".equals(name) && value == com.fasterxml.jackson.core.JsonToken.START_OBJECT) {
                    while (found == null && parser.nextToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME) {
                        String inner = parser.currentName();
                        com.fasterxml.jackson.core.JsonToken innerValue = parser.nextToken();
                        if ("code".equals(inner) && isCodeToken(innerValue)) {
                            found = codeValue(parser.getText());
                        } else {
                            parser.skipChildren();
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            }
            return found;
        } catch (IOException | RuntimeException e) {
            // тело обрезано или не JSON — кода нет
            return null;
        }
    }

    /** Код — строка или число; null и boolean кодом не считаются. */
    private static boolean isCodeToken(final com.fasterxml.jackson.core.JsonToken token) {
        return token == com.fasterxml.jackson.core.JsonToken.VALUE_STRING
            || token == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT;
    }

    /**
     * Метод по OpenTelemetry: известный — как есть, любой другой — {@code _OTHER}, а исходный пишется в
     * {@code http.request.method_original}.
     */
    static String otelMethod(final String method) {
        return method != null && KNOWN_METHODS.contains(method) ? method : "_OTHER";
    }

    static boolean knownMethod(final String method) {
        return method != null && KNOWN_METHODS.contains(method);
    }

    /**
     * Query с сохранёнными ключами и скрытыми значениями: {@code lang=REDACTED&cardId=REDACTED}. OpenTelemetry велит
     * скрывать значения чувствительных параметров с сохранением ключа; в запросах банка ПДн бывают в любом параметре,
     * поэтому скрываются все значения. Ключ проходит маскер текста лога.
     */
    static String redactQuery(final String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return null;
        }
        StringBuilder result = new StringBuilder(rawQuery.length());
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append('&');
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            result.append(LogTextMasker.mask(key));
            if (eq >= 0) {
                result.append('=').append(REDACTED);
            }
        }
        return result.isEmpty() ? null : result.toString();
    }

    /** {@code HTTP/1.1} → {@code 1.1}, {@code HTTP/2.0} → {@code 2}, как в примерах OpenTelemetry. */
    static String protocolVersion(final String protocol) {
        if (protocol == null) {
            return null;
        }
        int slash = protocol.indexOf('/');
        String version = slash < 0 ? protocol : protocol.substring(slash + 1);
        if (version.endsWith(".0") && !version.startsWith("1")) {
            version = version.substring(0, version.length() - 2);
        }
        return version.isEmpty() ? null : version;
    }

    private static String codeValue(final String text) {
        if (text == null || text.isBlank() || text.length() > ERROR_CODE_MAX || text.contains("*")) {
            return null;
        }
        return text;
    }

    /**
     * Заголовки, которые не пишутся никогда, даже если сервис добавил их в allowed-headers: учётные данные,
     * cookie и ПДн из заголовков, которые прежняя библиотека тоже исключала (UserPinfl, UserPhone).
     */
    static boolean isSecretHeader(final String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SECRET_HEADERS.contains(lower) || lower.contains("token") || lower.contains("secret")
            || lower.contains("password") || lower.contains("pinfl") || lower.contains("phone")
            || lower.contains("api-key") || lower.contains("apikey");
    }

    private static boolean isJsonOrUnknown(final String contentType) {
        return contentType == null || contentType.toLowerCase(Locale.ROOT).contains("json");
    }

    private static boolean isXml(final String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("xml");
    }

    private static boolean isText(final String contentType) {
        return contentType.toLowerCase(Locale.ROOT).startsWith("text/");
    }

    /**
     * Текстовое тело (text/plain, text/csv, ошибочно объявленный JSON) через маскер строк лога. У обрезанного тела
     * отбрасывается хвост из цифр: в лог не попадает часть номера, оборванного на лимите.
     */
    String maskText(final byte[] data, final int length, final boolean truncated, final String charset) {
        java.nio.charset.Charset decoding = java.nio.charset.StandardCharsets.UTF_8;
        if (charset != null && java.nio.charset.Charset.isSupported(charset)) {
            decoding = java.nio.charset.Charset.forName(charset);
        }
        String text = new String(data, 0, length, decoding);
        if (truncated) {
            int end = text.length();
            while (end > 0 && (Character.isDigit(text.charAt(end - 1)) || text.charAt(end - 1) == ' '
                || text.charAt(end - 1) == '-' || text.charAt(end - 1) == '\uFFFD')) {
                end--;
            }
            text = text.substring(0, end);
        }
        String masked = LogTextMasker.mask(text, masker.rules());
        return truncated ? masked + LogTextMasker.TRUNCATED_MARK : masked;
    }

    /** Кодировка из Content-Type ({@code text/xml; charset=windows-1251}) или {@code null}. */
    static String charsetOf(final String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                String value = trimmed.substring(8).trim();
                return value.replace("\"", "").isEmpty() ? null : value.replace("\"", "");
            }
        }
        return null;
    }
}
