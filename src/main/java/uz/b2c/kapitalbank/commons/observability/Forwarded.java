package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * Адрес запроса глазами клиента, как требует OpenTelemetry для HTTP server: {@code url.scheme}, {@code server.address},
 * {@code server.port} берутся из {@code Forwarded} / {@code X-Forwarded-*}, если запрос пришёл через ingress или gateway,
 * иначе из самого запроса; {@code client.address} — первый адрес из этих заголовков или адрес соединения.
 * Значения из заголовков очищаются ({@link Correlation#sanitize}) и ограничены по длине.
 */
final class Forwarded {

    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;

    private Forwarded() {
    }

    static String scheme(final HttpServletRequest request) {
        String proto = first(request.getHeader("X-Forwarded-Proto"));
        if (proto == null) {
            proto = forwardedParam(request, "proto");
        }
        return proto != null ? proto.toLowerCase(Locale.ROOT) : request.getScheme();
    }

    static String host(final HttpServletRequest request) {
        String host = first(request.getHeader("X-Forwarded-Host"));
        if (host == null) {
            host = forwardedParam(request, "host");
        }
        return host != null ? stripPort(host) : request.getServerName();
    }

    static Integer port(final HttpServletRequest request) {
        String port = first(request.getHeader("X-Forwarded-Port"));
        if (port != null) {
            return parsePort(port);
        }
        String host = first(request.getHeader("X-Forwarded-Host"));
        if (host == null) {
            host = forwardedParam(request, "host");
        }
        Integer fromHost = host == null ? null : portOf(host);
        if (fromHost != null) {
            return fromHost;
        }
        boolean forwarded = host != null || first(request.getHeader("X-Forwarded-Proto")) != null
            || forwardedParam(request, "proto") != null;
        if (forwarded) {
            return "https".equals(scheme(request)) ? HTTPS_PORT : HTTP_PORT;
        }
        int local = request.getServerPort();
        return local > 0 ? local : null;
    }

    static String client(final HttpServletRequest request) {
        String client = first(request.getHeader("X-Forwarded-For"));
        if (client == null) {
            client = forwardedParam(request, "for");
        }
        if (client != null) {
            // Forwarded: for="[2001:db8::1]:4711" или for=192.0.2.60:4711
            client = client.startsWith("[") && client.indexOf(']') > 0
                ? client.substring(1, client.indexOf(']')) : client.indexOf(':') == client.lastIndexOf(':')
                ? stripPort(client) : client;
        }
        return client != null ? client : request.getRemoteAddr();
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

    private static String stripPort(final String host) {
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            return end > 0 ? host.substring(1, end) : host;
        }
        int colon = host.indexOf(':');
        return colon < 0 ? host : host.substring(0, colon);
    }

    private static Integer portOf(final String host) {
        int colon = host.startsWith("[") ? host.indexOf(':', Math.max(0, host.indexOf(']'))) : host.indexOf(':');
        return colon < 0 ? null : parsePort(host.substring(colon + 1));
    }

    private static Integer parsePort(final String port) {
        try {
            int value = Integer.parseInt(port.trim());
            return value > 0 && value <= 65535 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
