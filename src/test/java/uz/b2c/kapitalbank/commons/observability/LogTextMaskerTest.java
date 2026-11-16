package uz.b2c.kapitalbank.commons.observability;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LogTextMaskerTest {

    private static final String PAN = "8600123456781233";

    private final MaskingRules rules = MaskingRules.base();

    @Test
    void masksLombokToString() {
        String text = "card: CardDto(pan=" + PAN + ", holderFullName=IVAN IVANOV, phone=998901234567, cvv=123)";

        String masked = LogTextMasker.mask(text, rules);

        assertThat(masked).doesNotContain(PAN).contains("pan=860012******1233")
            .contains("holderFullName=I***").doesNotContain("IVANOV")
            .contains("phone=***4567").contains("cvv=***");
    }

    @Test
    void masksJsonInMessage() {
        String text = "response {\"cardNumber\":\"" + PAN + "\",\"otp\":\"5521\",\"passport\":{\"series\":\"AA\"},"
            + "\"code\":\"7781\",\"status\":\"OK\"}";

        String masked = LogTextMasker.mask(text, rules);

        assertThat(masked).doesNotContain(PAN).doesNotContain("5521").doesNotContain("7781")
            .doesNotContain("\"series\"").contains("\"status\":\"OK\"");
    }

    @Test
    void keepsNumericErrorCodesAndNulls() {
        String masked = LogTextMasker.mask("error {\"code\":40012, \"pan\":null}", rules);

        assertThat(masked).isEqualTo("error {\"code\":40012, \"pan\":null}");
    }

    @Test
    void masksBareCardNumberAnywhere() {
        String masked = LogTextMasker.mask("transfer from " + PAN + " failed", rules);

        assertThat(masked).isEqualTo("transfer from 860012******1233 failed");
    }

    @Test
    void doesNotTouchSimilarWords() {
        String text = "Japan: tokyo, spinner=3, account opened, PAN check failed";

        assertThat(LogTextMasker.mask(text, rules)).isEqualTo(text);
    }

    @Test
    void masksHttpClientExceptionMessage() {
        String text = "400 Bad Request: \"{\"phoneNumber\":\"998901234567\",\"message\":\"card " + PAN + " blocked\"}\"";

        String masked = LogTextMasker.mask(text, rules);

        assertThat(masked).doesNotContain("998901234567").doesNotContain(PAN).contains("blocked");
    }

    @Test
    void teamFieldsAreAddedToBase() {
        HttpLogProperties.Masking masking = new HttpLogProperties.Masking();
        masking.setHide(Set.of("loyaltyId"));
        MaskingRules withTeam = MaskingRules.of(masking);

        String masked = LogTextMasker.mask("loyaltyId=777, pan=" + PAN + ", token=abc", withTeam);

        assertThat(masked).isEqualTo("loyaltyId=***, pan=860012******1233, token=***");
    }
}
