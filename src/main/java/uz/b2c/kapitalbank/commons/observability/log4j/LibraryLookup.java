package uz.b2c.kapitalbank.commons.observability.log4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.lookup.StrLookup;

/**
 * Lookup {@code ${libobservability:name}} и {@code ${libobservability:version}} — имя и версия библиотеки для полей
 * {@code uz.kapitalbank.log_library.name} и {@code uz.kapitalbank.log_library.version}: по ним в логах видно, какой
 * версией библиотеки сделана запись. Не {@code telemetry.distro.*}: в OpenTelemetry это дистрибутив OTel (агент),
 * и с подключением OTel Java agent одно поле получило бы два смысла. Версию подставляет Gradle в {@code version.properties}.
 */
@Plugin(name = "libobservability", category = StrLookup.CATEGORY)
public final class LibraryLookup implements StrLookup {

    static final String NAME = "observability-starter";
    static final String VERSION = readVersion();

    @Override
    public String lookup(final String key) {
        return "name".equals(key) ? NAME : "version".equals(key) ? VERSION : null;
    }

    @Override
    public String lookup(final LogEvent event, final String key) {
        return lookup(key);
    }

    private static String readVersion() {
        Properties properties = new Properties();
        try (InputStream in = LibraryLookup.class.getResourceAsStream("/lib-observability/version.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            // версия неизвестна — запись всё равно пишется
        }
        String version = properties.getProperty("version");
        // без подстановки Gradle (запуск из IDE) — неизвестна
        return version == null || version.isBlank() || version.startsWith("$") ? "unknown" : version;
    }
}
