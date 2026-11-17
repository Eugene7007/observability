package uz.b2c.kapitalbank.commons.observability;

import org.apache.logging.log4j.Level;
import org.springframework.util.AntPathMatcher;

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
        this.maxBodyBytes = properties.getMaxBodyBytes();
        this.excludePaths = List.copyOf(properties.getExcludePaths());
        this.bodyPaths = List.copyOf(properties.getBodyPaths());
        this.allowedHeaders = properties.getAllowedHeaders().stream()
            .map(header -> header.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
        this.masker = new JsonBodyMasker(properties.getMasking());
        this.xmlmasker = new XmlBodyMasker(properties.getMasking());
        this.bodies = properties.getBodies();
    }

    /**
     * С какого уровня записи нужно захватывать тела. При ON_ERROR тела попадают в записи об ошибках,
     * а 5xx и исключения пишутся на WARN, поэтому захват работает и при уровне логгера WARN.
     */
    Level bodyCaptureLevel() {
        return bodies == HttpLogProperties.Bodies.ALWAYS ? Level.INFO : Level.WARN;
    }

    /**
     * Писать ли захваченные тела в эту запись: всегда или только при статусе 4xx/5xx и исключении.
     */
    boolean includeBodies(final Integer status, final boolean failed) {
        return bodies == HttpLogProperties.Bodies.ALWAYS
            || failed || (status != null && status >= CLIENT_ERROR);
    }

    int maxBodyBytes() {
        return maxBodyBytes;
    }

    boolean isExcluded(final String path) {
        return path != null && matchesAny(excludePaths, path);
    }

    boolean logBodies(final String path) {
        return bodies != HttpLogProperties.Bodies.OFF && maxBodyBytes > 0 && path != null
            && matchesAny(bodyPaths, path);
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
     * Тело для лога: замаскированный JSON, либо только тип и размер, если это не JSON.
     */
    String renderBody(final BodyCapture capture, final String contentType) {
        String rendered = null;
        if (capture != null && capture.total() > 0) {
            if (isXml(contentType)) {
                rendered = xmlmasker.mask(capture.buffer(), capture.size(), capture.truncated());
            } else {
                rendered = isJsonOrUnknown(contentType)
                    ? masker.mask(capture.buffer(), capture.size(), capture.truncated())
                    : "[" + contentType + ", " + capture.total() + " bytes]";
            }
        }
        return rendered;
    }

    String renderBody(final byte[] body, final String contentType) {
        String rendered = null;
        if (body != null && body.length > 0) {
            int length = Math.min(body.length, maxBodyBytes);
            rendered = isJsonOrUnknown(contentType)
                ? masker.mask(body, length, body.length > length)
                : "[" + contentType + ", " + body.length + " bytes]";
        }
        return rendered;
    }

    /**
     * Заголовки из allowlist в виде {@code name=value; name=value}. Остальные не попадают в лог совсем.
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
}
