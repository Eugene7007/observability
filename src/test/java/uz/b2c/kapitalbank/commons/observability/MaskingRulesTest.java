package uz.b2c.kapitalbank.commons.observability;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingRulesTest {

    @Test
    void baseRulesCannotBeReplacedByConfiguration() {
        HttpLogProperties.Masking masking = new HttpLogProperties.Masking();
        // раньше такой список заменял базовый целиком, и поле pan переставало маскироваться
        masking.setPan(Set.of("virtualCard"));
        JsonBodyMasker masker = new JsonBodyMasker(masking);
        byte[] body = "{\"pan\":\"8600123456781233\",\"virtualCard\":\"8600123456785555\",\"cvv\":\"123\"}"
            .getBytes(StandardCharsets.UTF_8);

        String masked = masker.mask(body, body.length, false);

        assertThat(masked).isEqualTo(
            "{\"pan\":\"860012******1233\",\"virtualCard\":\"860012******5555\",\"cvv\":\"***\"}");
    }

    @Test
    void panInsideAnyStringIsAlwaysMasked() {
        JsonBodyMasker masker = new JsonBodyMasker(MaskingRules.base());
        byte[] body = "{\"description\":\"card 8600123456781233 blocked\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(masker.mask(body, body.length, false))
            .isEqualTo("{\"description\":\"card 860012******1233 blocked\"}");
    }

    @Test
    void installedRulesAreUsedForLogLines() {
        MaskingRules previous = MaskingRules.current();
        try {
            HttpLogProperties.Masking masking = new HttpLogProperties.Masking();
            masking.setPhone(Set.of("contact"));
            MaskingRules.install(MaskingRules.of(masking));

            assertThat(LogTextMasker.mask("contact=998901234567")).isEqualTo("contact=***4567");
        } finally {
            MaskingRules.install(previous);
        }
    }
}
