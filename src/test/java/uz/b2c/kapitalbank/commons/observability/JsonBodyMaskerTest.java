package uz.b2c.kapitalbank.commons.observability;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Канареечные значения: тестовые PAN, срок, OTP, ПИНФЛ, телефон и ФИО не должны попадать в лог ни в каком виде.
 */
class JsonBodyMaskerTest {

    private static final String PAN = "8600510343218413";
    private static final String PAN_MIDDLE = "1034321";

    private final JsonBodyMasker masker = new JsonBodyMasker(new HttpLogProperties.Masking());

    private String mask(final String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return masker.mask(bytes, bytes.length, false);
    }

    @Test
    void masksSvGateRegistrationRequest() {
        String json = """
            {"jsonrpc":"2.0","method":"cards.new.otp","id":"1","params":{
              "card":{"pan":"%s","expiry":"2812","requestorPhone":"+998901234567"},
              "otp":{"id":15,"code":"123456"},"pinfl":"31234567890123"}}""".formatted(PAN);

        String masked = mask(json);

        assertThat(masked)
            .contains("\"method\":\"cards.new.otp\"")
            .contains("\"pan\":\"860051******8413\"")
            .contains("\"expiry\":\"***\"")
            .contains("\"requestorPhone\":\"***4567\"")
            .contains("\"otp\":\"***\"")
            .contains("\"pinfl\":\"***\"")
            .doesNotContain(PAN_MIDDLE, "2812", "123456", "31234567890123", "90123");
    }

    @Test
    void hidesOtpCodeStringButKeepsNumericErrorCode() {
        String request = mask("{\"id\":15,\"code\":\"654321\"}");
        String error = mask("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-200,\"message\":\"Card not found\"}}");

        assertThat(request).isEqualTo("{\"id\":15,\"code\":\"***\"}");
        assertThat(error).contains("\"code\":-200").contains("Card not found");
    }

    @Test
    void keepsAlreadyMaskedPanAndMasksNamesAndArrays() {
        String json = """
            {"content":[{"hpan":"860051******0111","holderFullName":"ИВАНОВ ИВАН","phone":"998901112233"},
                        {"hpan":"%s","EMBOS_NAME":"PETROV P"}]}""".formatted(PAN);

        String masked = mask(json);

        assertThat(masked)
            .contains("\"hpan\":\"860051******0111\"")
            .contains("\"hpan\":\"860051******8413\"")
            .contains("\"holderFullName\":\"И***\"")
            .contains("\"EMBOS_NAME\":\"P***\"")
            .contains("\"phone\":\"***2233\"")
            .doesNotContain("ИВАНОВ", "PETROV", PAN_MIDDLE);
    }

    @Test
    void masksPanUnderCrefNoAndAccountNumbers() {
        String masked = mask("{\"CREF_NO\":\"8600510343218414\",\"aacct\":\"20206000904920154001\","
            + "\"accNo\":\"22618000600001234567\"}");

        assertThat(masked)
            .isEqualTo("{\"CREF_NO\":\"860051******8414\",\"aacct\":\"***4001\",\"accNo\":\"***4567\"}");
    }

    @Test
    void findsPanInsideFreeText() {
        String masked = mask("{\"description\":\"Перевод с карты " + PAN + " выполнен\",\"rrn\":\"123456789012\"}");

        assertThat(masked)
            .contains("с карты 860051******8413 выполнен")
            .contains("\"rrn\":\"123456789012\"")
            .doesNotContain(PAN_MIDDLE);
    }

    @Test
    void truncatedBodyNeverLeaksHalfOfValue() {
        String json = "{\"id\":\"1\",\"params\":{\"amount\":100,\"description\":\"card " + PAN + " ok\"}}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        int cut = json.indexOf(PAN) + 8;

        String masked = masker.mask(bytes, cut, true);

        assertThat(masked)
            .startsWith("{\"id\":\"1\",\"params\":{\"amount\":100")
            .endsWith(JsonBodyMasker.TRUNCATED_MARK)
            .doesNotContain("86005103");
    }

    @Test
    void nonJsonBodyIsNotLoggedAsIs() {
        byte[] bytes = ("pan=" + PAN + "&expiry=2812").getBytes(StandardCharsets.UTF_8);

        String masked = masker.mask(bytes, bytes.length, false);

        assertThat(masked).isEqualTo("[unparseable body, " + bytes.length + " bytes]");
    }

    @Test
    void pathRuleMatchesOnlyNestedField() {
        HttpLogProperties.Masking rules = new HttpLogProperties.Masking();
        rules.setHide(java.util.Set.of("sms.text"));
        JsonBodyMasker pathMasker = new JsonBodyMasker(rules);
        byte[] bytes = "{\"text\":\"visible\",\"sms\":{\"text\":\"secret\"}}".getBytes(StandardCharsets.UTF_8);

        String masked = pathMasker.mask(bytes, bytes.length, false);

        assertThat(masked).isEqualTo("{\"text\":\"visible\",\"sms\":{\"text\":\"***\"}}");
    }

    @Test
    void nameValuePairsAreMaskedInAnyFieldOrderAndBusinessPairsStay() {
        JsonBodyMasker masker = new JsonBodyMasker(MaskingRules.base());
        String json = "{\"params\":[{\"name\":\"password\",\"value\":\"x1\"},{\"name\":\"amount\",\"value\":\"100\"},"
            + "{\"value\":\"200\",\"name\":\"sum\"},{\"value\":\"x2\",\"key\":\"cvv\"}],\"value\":\"top\","
            + "\"a\":{\"b\":{\"value\":\"v1\"},\"name\":\"cvv\",\"value\":\"x3\"}}";
        byte[] data = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(masker.mask(data, data.length, false)).isEqualTo(
            "{\"params\":[{\"name\":\"password\",\"value\":\"***\"},{\"name\":\"amount\",\"value\":\"100\"},"
                + "{\"value\":\"200\",\"name\":\"sum\"},{\"value\":\"***\",\"key\":\"cvv\"}],\"value\":\"top\","
                + "\"a\":{\"b\":{\"value\":\"v1\"},\"name\":\"cvv\",\"value\":\"***\"}}");
    }

    @Test
    void valueOfObjectCutBeforeItsNameIsHidden() {
        JsonBodyMasker masker = new JsonBodyMasker(MaskingRules.base());
        byte[] data = "[{\"value\":\"S3cr3t\",\"key\":\"password\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(masker.mask(data, 20, true)).doesNotContain("S3cr3t").contains("\"value\":\"***\"");
    }
}
