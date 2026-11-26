package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * Адрес запроса глазами клиента, как требует OpenTelemetry для HTTP server. Источники — в порядке, который задаёт
 * стандарт: {@code url.scheme} — {@code Forwarded#proto}, {@code X-Forwarded-Proto}, затем схема соединения;
 * {@code server.address} и {@code server.port} — {@code Forwarded#host}, {@code X-Forwarded-Host}, {@code Host};
 * {@code client.address} — {@code Forwarded#for}, {@code X-Forwarded-For}, затем адрес соединения.
 * <p>
 * Заголовки присылает клиент или прокси, поэтому значения очищаются, проверяются по формату и проходят маскер
 * текста лога: в лог не попадает ни произвольная строка вместо схемы, ни номер карты вместо адреса.
 * {@code client.address} из заголовков доверять нельзя: левое значение {@code X-Forwarded-For} задаёт клиент.
 */
final class Forwarded {

    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;
    private static final int MAX_PORT = 65535;

    private Forwarded() {
    }

    static String scheme(final HttpServletRequest request) {
        String proto = forwardedParam(request, "proto");
        if (proto == null) {
            proto = first(request.getHeader("X-Forwarded-Proto"));
        }
        String scheme = proto == null ? null : proto.toLowerCase(Locale.ROOT);
        // только http и https: остальное — мусор из заголовка, а не схема
        return "http".equals(scheme) || "https".equals(scheme) ? scheme : request.getScheme();
    }

    /** {@code server.address}: хост без порта, IPv6 без скобок, замаскированный. */
    static String host(final HttpServletRequest request) {
        String host = authority(request);
        String name = host != null ? stripPort(host) : request.getServerName();
        return name == null || name.isEmpty() ? null : LogTextMasker.maskOrPlaceholder(name);
    }

    /** {@code server.port}: X-Forwarded-Port, порт из authority, иначе порт по схеме или порт соединения. */
    static Integer port(final HttpServletRequest request) {
        Integer forwardedPort = parsePort(first(request.getHeader("X-Forwarded-Port")));
        if (forwardedPort != null) {
            return forwardedPort;
        }
        String host = authority(request);
        if (host != null) {
            Integer fromHost = portOf(host);
            return fromHost != null ? fromHost : defaultPort(scheme(request));
        }
        int local = request.getServerPort();
        return local > 0 ? local : null;
    }

    /** {@code client.address}: IP или имя клиента без порта, замаскированное; обфусцированные значения RFC 7239 — нет. */
    static String client(final HttpServletRequest request) {
        String client = forwardedParam(request, "for");
        if (client == null) {
            client = first(request.getHeader("X-Forwarded-For"));
        }
        if (client != null) {
            client = stripPort(client);
            // for=unknown и for=_hidden (RFC 7239) — не адрес
            if (client.isEmpty() || "unknown".equalsIgnoreCase(client) || client.startsWith("_")) {
                client = null;
            }
        }
        if (client == null) {
            client = request.getRemoteAddr();
        }
        return client == null || client.isEmpty() ? null : LogTextMasker.maskOrPlaceholder(client);
    }

    /** Authority, по которой обратился клиент: Forwarded#host, X-Forwarded-Host, Host. */
    private static String authority(final HttpServletRequest request) {
        String host = forwardedParam(request, "host");
        if (host == null) {
            host = first(request.getHeader("X-Forwarded-Host"));
        }
        if (host == null) {
            host = Correlation.sanitize(request.getHeader("Host"));
        }
        return host;
    }

    private static Integer defaultPort(final String scheme) {
        return "https".equals(scheme) ? HTTPS_PORT : HTTP_PORT;
    }

    private static String first(final String header) {
        if (header == null) {
            return null;
        }
        int comma = header.indexOf(',');
        return Correlation.sanitize(comma < 0 ? header : header.substring(0, comma));
    }

    /** Параметр первого элемента заголовка {@code Forwarded} (RFC 7239). */
    private static String forwardedParam(final HttpServletRequest request, final String name) {
        String forwarded = request.getHeader("Forwarded");
        if (forwarded == null) {
            return null;
        }
        int comma = forwarded.indexOf(',');
        String element = comma < 0 ? forwarded : forwarded.substring(0, comma);
        for (String pair : element.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equalsIgnoreCase(name)) {
                String value = pair.substring(eq + 1).trim();
                if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return Correlation.sanitize(value);
            }
        }
        return null;
    }

    /**
     * Хост без порта: {@code [2001:db8::1]:4711} → {@code 2001:db8::1}, {@code host:8080} → {@code host}; IPv6 без
     * скобок и без порта ({@code 2001:db8::1}) — как есть.
     */
    static String stripPort(final String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 ? host.substring(1, end) : host;
        }
        int colon = host.indexOf(':');
        if (colon < 0 || colon != host.lastIndexOf(':')) {
            return host;
        }
        return host.substring(0, colon);
    }

    private static Integer portOf(final String host) {
        int colon;
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            colon = end > 0 && end + 1 < host.length() && host.charAt(end + 1) == ':' ? end + 1 : -1;
        } else {
            colon = host.indexOf(':') == host.lastIndexOf(':') ? host.indexOf(':') : -1;
        }
        return colon < 0 ? null : parsePort(host.substring(colon + 1));
    }

    private static Integer parsePort(final String port) {
        if (port == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(port.trim());
            return value > 0 && value <= MAX_PORT ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
