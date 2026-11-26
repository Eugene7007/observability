package uz.b2c.kapitalbank.commons.observability;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Находки ревью 2026-11-25 по словарю маскирования, после исправления. Имена взяты из кода 74 сервисов банка
 * и из процессинговых интерфейсов Tieto/Humo.
 */
class DictionaryReview20261125Test {

    private final MaskingRules rules = MaskingRules.base();
    private final HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

    private String json(final String body) {
        return settings.renderBody(body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    @Test
    void personalDataNamesFromBankCodeHaveRules() {
        for (String name : List.of(
            // ФИО с суффиксом языка или алфавита, отчество
            "firstNameEn", "firstNameLatin", "lastNameLat", "surnameLatin", "surnameCyr", "patronym", "patronymLatin",
            "secondNameLat", "clientSecondName", "recipientNameLocal")) {
            assertThat(rules.ruleFor(name)).as(name).isEqualTo(MaskingRules.Rule.NAME);
        }
        for (String name : List.of(
            "address1", "address2", "documentSeria", "documentSerialNumber", "docIssueDate", "docGivePlace", "birthPlace",
            "identityDocumentIssuer", "taxPayerCode", "expirationMonth", "expirationYear", "accessKey", "authCred", "salt")) {
            assertThat(rules.ruleFor(name)).as(name).isEqualTo(MaskingRules.Rule.HIDE);
        }
        for (String name : List.of("telefon", "phones")) {
            assertThat(rules.ruleFor(name)).as(name).isEqualTo(MaskingRules.Rule.TAIL);
        }
    }

    @Test
    void trailingDigitsOnAnyWordKeepTheRule() {
        for (String name : List.of("EXP_DATE1", "BIRTH_DATE2", "PERSON_CODE2", "address1", "ADDRESS2")) {
            assertThat(rules.ruleFor(name)).as(name).isEqualTo(MaskingRules.Rule.HIDE);
        }
        assertThat(rules.ruleFor("CARD_NUMBER2")).isEqualTo(MaskingRules.Rule.PAN);
        assertThat(rules.ruleFor("cardNumber2")).isEqualTo(MaskingRules.Rule.PAN);
        assertThat(rules.ruleFor("firstName2")).isEqualTo(MaskingRules.Rule.NAME);
        // слова с цифрой в словаре по-прежнему узнаются как есть
        assertThat(rules.ruleFor("cvv2")).isEqualTo(MaskingRules.Rule.HIDE);
        assertThat(rules.ruleFor("track1")).isEqualTo(MaskingRules.Rule.HIDE);
    }

    @Test
    void threeDigitCardSecretsAreHiddenUnderAnyName() {
        assertThat(json("{\"securityCode\":\"739\"}")).doesNotContain("739");
        assertThat(json("{\"securityCode\":739}")).doesNotContain("739");
        for (String name : List.of("csc", "cvn", "card_verification_value", "secureCode", "cardSecurityCode", "cvv2")) {
            assertThat(rules.ruleFor(name)).as(name).isEqualTo(MaskingRules.Rule.HIDE);
            assertThat(json("{\"" + name + "\":\"739\"}")).as(name).doesNotContain("739");
        }
        String text = LogTextMasker.mask("securityCode=739 csc=739 cvv2=739");
        assertThat(text).doesNotContain("739");
        // коды подтверждения из SMS по-прежнему по правилу «4–8 цифр»: бизнес-коды остаются
        assertThat(rules.ruleFor("verificationCode")).isEqualTo(MaskingRules.Rule.DIGITS);
    }

    @Test
    void processingClientFieldsAreMasked() {
        assertThat(rules.ruleFor("M_NAME")).isEqualTo(MaskingRules.Rule.NAME);
        assertThat(rules.ruleFor("E_MAILS")).isEqualTo(MaskingRules.Rule.EMAIL);
        assertThat(rules.ruleFor("R_E_MAILS")).isEqualTo(MaskingRules.Rule.EMAIL);
        String xml = settings.renderBody("<row><M_NAME>Ivanovich</M_NAME><E_MAILS>ivan@mail.uz</E_MAILS></row>"
            .getBytes(StandardCharsets.UTF_8), "text/xml");
        assertThat(xml).doesNotContain("Ivanovich").doesNotContain("ivan@mail.uz");
    }

    @Test
    void businessFieldsAreNoLongerOverMasked() {
        for (String name : List.of("terminalSerialNo", "posSerialNo", "deviceSerialNo", "PCODE",
            "authorizationDateTime", "birthCountryId")) {
            assertThat(rules.ruleFor(name)).as(name).isNull();
        }
        // точные имена процессинга остаются скрытыми
        assertThat(rules.ruleFor("SERIAL_NO")).isEqualTo(MaskingRules.Rule.HIDE);
        assertThat(rules.ruleFor("R_PCODE")).isEqualTo(MaskingRules.Rule.HIDE);
        // заголовок авторизации и его варианты — секрет
        for (String name : List.of("Authorization", "proxyAuthorization", "authorizationHeader", "authorizationToken",
            // ревью 2026-11-26: код и ответ авторизации — секрет, пока не подтверждено обратное
            "authorizationCode", "authorizationResponse")) {
            assertThat(rules.ruleFor(name)).as(name).isEqualTo(MaskingRules.Rule.HIDE);
        }
        // cardName — открытый вопрос к командам (название карты или имя на ней), пока скрывается как имя
        assertThat(rules.ruleFor("cardName")).isEqualTo(MaskingRules.Rule.NAME);
    }
}
