package uz.b2c.kapitalbank.commons.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uz.b2c.kapitalbank.commons.observability.MaskingRules.Rule;

/**
 * Снимок словаря: правило базы для каждого имени из корпуса — реальные имена полей всех сервисов банка плюс
 * придуманные ревьюверами. Любое изменение правил роняет тест со списком изменённых имён, ослабления — первыми.
 * Так правка словаря не может тихо открыть поле, которое раньше маскировалось (ревью 2026-11-26, HIGH-1).
 * <p>
 * После осознанного изменения: {@code ./gradlew test -PupdateMaskingSnapshot} перезаписывает снимок, а дифф снимка
 * в PR показывает ревьюеру и командам каждое изменённое поле.
 */
class MaskingDictionarySnapshotTest {

    private static final String RESOURCE = "/masking-dictionary-snapshot.tsv";
    private static final String NONE = "-";

    @Test
    void dictionaryMatchesSnapshot() throws IOException {
        Map<String, String> snapshot = read();
        MaskingRules rules = MaskingRules.base();
        List<String> weakened = new ArrayList<>();
        List<String> strengthened = new ArrayList<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            Rule rule = rules.ruleFor(entry.getKey());
            String now = rule == null ? NONE : rule.name();
            actual.put(entry.getKey(), now);
            if (!now.equals(entry.getValue())) {
                String line = entry.getKey() + ": " + entry.getValue() + " -> " + now;
                if (strictness(now) < strictness(entry.getValue())) {
                    weakened.add(line);
                } else {
                    strengthened.add(line);
                }
            }
        }
        String update = System.getProperty("masking.snapshot.update");
        if (update != null && !update.isBlank()) {
            write(Path.of(update), actual);
            return;
        }
        assertThat(weakened)
            .as("Ослабление маскирования (поле открылось или маскируется мягче). Если это осознанно — "
                + "./gradlew test -PupdateMaskingSnapshot и подтверждение команд в PR")
            .isEmpty();
        assertThat(strengthened)
            .as("Словарь изменился. Обновите снимок: ./gradlew test -PupdateMaskingSnapshot")
            .isEmpty();
        assertThat(snapshot).as("снимок не пуст").hasSizeGreaterThan(1000);
    }

    private static int strictness(final String rule) {
        return NONE.equals(rule) ? 0 : Rule.valueOf(rule).strictness();
    }

    private static Map<String, String> read() throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (InputStream in = MaskingDictionarySnapshotTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as(RESOURCE).isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                result.put(line.substring(0, tab), line.substring(tab + 1).trim());
            }
        }
        return result;
    }

    private static void write(final Path file, final Map<String, String> rules) throws IOException {
        StringBuilder out = new StringBuilder()
            .append("# Снимок словаря маскирования: имя поля<TAB>правило базы (- — не ПДн).\n")
            .append("# Корпус: имена полей всех сервисов банка и придуманные ревьюверами. Добавлять имена можно,\n")
            .append("# правила менять — только через ./gradlew test -PupdateMaskingSnapshot (см. MaskingDictionarySnapshotTest).\n");
        rules.forEach((name, rule) -> out.append(name).append('\t').append(rule).append('\n'));
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }
}
