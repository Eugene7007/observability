package uz.b2c.kapitalbank.commons.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uz.b2c.kapitalbank.commons.observability.MaskingRules.Rule;

/**
 * Находки ревью 2026-11-26: сужение authorization (HIGH-1), расширение базы ослабляло поля команды (HIGH-2),
 * номер отдельным словом на конце имени (LOW-2).
 */
class DictionaryReview20261126Test {

    private final MaskingRules base = MaskingRules.base();

    @Test
    void authorizationAnywhereInNameIsSecret() {
        for (String name : List.of("authorization", "Authorization", "authorizationKey", "AUTHORIZATION_KEY",
            "authorizationValue", "authorizationString", "authorizationBearer", "authorizationData",
            "authorizationHeaderValue", "authorizationCode", "authorization_code", "authorizationResponse",
            "proxyAuthorization", "authorizationTokenId", "authorizationIdToken")) {
            assertThat(base.ruleFor(name)).as(name).isEqualTo(Rule.HIDE);
        }
        byte[] body = "{\"authorizationKey\":\"k3y-5ecr3t\",\"authorizationValue\":\"Basic dXNlcjpwYXNz\"}"
            .getBytes(StandardCharsets.UTF_8);
        assertThat(new JsonBodyMasker(base).mask(body, body.length, false))
            .doesNotContain("k3y-5ecr3t").doesNotContain("dXNlcjpwYXNz");
        assertThat(LogTextMasker.mask("authorizationKey=k3y-5ecr3t", base)).doesNotContain("k3y-5ecr3t");
    }

    @Test
    void businessAuthorizationFieldsStayOpen() {
        for (String name : List.of("authorizationDateTime", "authorizationDate", "authorizationTime",
            "authorizationStatus", "authorizationId", "authorizationType", "authorization_status")) {
            assertThat(base.ruleFor(name)).as(name).isNull();
        }
    }

    @Test
    void teamHideIsNotWeakenedByBaseRules() {
        HttpLogProperties.Masking masking = new HttpLogProperties.Masking();
        masking.setHide(Set.of("code1", "phones", "CARD_ACCT", "cardName", "firstNameEn", "email1", "transfer.cardName"));
        MaskingRules rules = MaskingRules.of(masking);

        for (String name : List.of("code1", "phones", "CARD_ACCT", "cardName", "firstNameEn", "email1")) {
            assertThat(rules.ruleFor(name)).as(name).isEqualTo(Rule.HIDE);
        }
        byte[] json = ("{\"code1\":\"ABC-SECRET\",\"phones\":\"+998901234567\",\"CARD_ACCT\":\"20208000900100200300\","
            + "\"transfer\":{\"cardName\":\"Bahodir\"}}").getBytes(StandardCharsets.UTF_8);
        String masked = new JsonBodyMasker(rules).mask(json, json.length, false);
        assertThat(masked).doesNotContain("ABC").doesNotContain("4567").doesNotContain("0300").doesNotContain("B*");

        byte[] xml = "<r><code1>ABC-SECRET</code1><phones>+998901234567</phones><transfer><cardName>Bahodir</cardName></transfer></r>"
            .getBytes(StandardCharsets.UTF_8);
        String maskedXml = new XmlBodyMasker(rules).mask(xml, xml.length, false);
        assertThat(maskedXml).doesNotContain("ABC").doesNotContain("4567").doesNotContain(">B");

        assertThat(LogTextMasker.mask("code1=ABC-SECRET phones=+998901234567", rules))
            .doesNotContain("ABC").doesNotContain("4567");
    }

    @Test
    void teamPathRuleIsNotWeakenedByBaseRules() {
        HttpLogProperties.Masking masking = new HttpLogProperties.Masking();
        masking.setHide(Set.of("transfer.cardName", "payload.phone"));
        MaskingRules rules = MaskingRules.of(masking);
        byte[] json = "{\"transfer\":{\"cardName\":\"Bahodir\"},\"payload\":{\"phone\":\"+998901234567\"},\"cardName\":\"Rustam\"}"
            .getBytes(StandardCharsets.UTF_8);
        String masked = new JsonBodyMasker(rules).mask(json, json.length, false);
        // по пути — скрыто целиком, вне пути — правило базы (первая буква имени)
        assertThat(masked).doesNotContain("B*").doesNotContain("4567").contains("R***");

        byte[] xml = "<r><transfer><cardName>Bahodir</cardName></transfer><payload><phone>+998901234567</phone></payload><cardName>Rustam</cardName></r>"
            .getBytes(StandardCharsets.UTF_8);
        String maskedXml = new XmlBodyMasker(rules).mask(xml, xml.length, false);
        assertThat(maskedXml).doesNotContain(">B").doesNotContain("4567").contains(">R***<");
    }

    @Test
    void teamCannotWeakenBase() {
        HttpLogProperties.Masking masking = new HttpLogProperties.Masking();
        masking.setName(Set.of("password", "pan"));
        masking.setCodes(Set.of("cvv"));
        MaskingRules rules = MaskingRules.of(masking);
        assertThat(rules.ruleFor("password")).isEqualTo(Rule.HIDE);
        assertThat(rules.ruleFor("cvv")).isEqualTo(Rule.HIDE);
        // имя строже номера карты: остаётся одна буква, а не 6+4 цифры
        assertThat(rules.ruleFor("pan")).isEqualTo(Rule.NAME);
    }

    @Test
    void teamFieldsMatchLanguageAndNumberVariants() {
        HttpLogProperties.Masking masking = new HttpLogProperties.Masking();
        masking.setHide(Set.of("loyaltyId"));
        MaskingRules rules = MaskingRules.of(masking);
        for (String name : List.of("loyaltyId", "loyaltyId2", "loyaltyIdEn", "loyalty_id_2", "LOYALTY_ID_UZ")) {
            assertThat(rules.ruleFor(name)).as(name).isEqualTo(Rule.HIDE);
        }
        assertThat(rules.ruleFor("loyaltyLevel")).isNull();
    }

    @Test
    void numberAsSeparateTrailingWordIsTheSameField() {
        assertThat(base.ruleFor("pin_2")).isEqualTo(Rule.HIDE);
        assertThat(base.ruleFor("pinEn1")).isEqualTo(Rule.HIDE);
        assertThat(base.ruleFor("PIN_EN_1")).isEqualTo(Rule.HIDE);
        assertThat(base.ruleFor("expiry_2")).isEqualTo(Rule.HIDE);
        assertThat(base.ruleFor("cardNumber_2")).isEqualTo(Rule.PAN);
        assertThat(base.ruleFor("card_number_2")).isEqualTo(Rule.PAN);
        assertThat(base.ruleFor("firstName_2")).isEqualTo(Rule.NAME);
        // одна буква без цифр — не слово словаря
        assertThat(base.ruleFor("p1")).isNull();
        assertThat(base.ruleFor("amount_2")).isNull();
    }

    @Test
    void stricterPicksTheRuleThatRevealsLess() {
        assertThat(MaskingRules.stricter(Rule.CODE, Rule.HIDE)).isEqualTo(Rule.HIDE);
        assertThat(MaskingRules.stricter(Rule.HIDE, Rule.NAME)).isEqualTo(Rule.HIDE);
        assertThat(MaskingRules.stricter(Rule.PAN, Rule.TAIL)).isEqualTo(Rule.TAIL);
        assertThat(MaskingRules.stricter(Rule.TAIL, Rule.NAME)).isEqualTo(Rule.NAME);
        assertThat(MaskingRules.stricter(null, Rule.URL)).isEqualTo(Rule.URL);
        assertThat(MaskingRules.stricter(null, null)).isNull();
    }
}
