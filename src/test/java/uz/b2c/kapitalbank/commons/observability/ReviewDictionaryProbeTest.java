package uz.b2c.kapitalbank.commons.observability;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ревью 2026-11-25: пробы словаря маскирования. Тест фиксирует ФАКТИЧЕСКОЕ поведение (находки ревью):
 * утверждения с пометкой FINDING описывают утечку или пере-маскирование и должны быть перевёрнуты вместе с исправлением.
 * Имена взяты из кода 74 сервисов банка (allnames.txt) или из процессинговых интерфейсов.
 */
class ReviewDictionaryProbeTest {

    private final MaskingRules rules = MaskingRules.base();
    private final HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

    private String json(final String body) {
        return settings.renderBody(body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    @Test
    void findingFalseNegativesInBankCode() {
        // FINDING: реальные имена ПДн из кода сервисов без правила
        for (String name : List.of(
            // ФИО с суффиксом языка/алфавита и «patronym»
            "firstNameEn", "firstNameLatin", "lastNameLat", "surnameLatin", "surnameCyr", "patronym", "patronymLatin",
            "secondNameLat", "clientSecondName",
            // адрес с номером, документы
            "address1", "address2", "documentSeria", "documentSerialNumber", "docIssueDate", "docGivePlace", "birthPlace",
            // ИНН под другим именем, срок карты, секреты
            "taxPayerCode", "expirationMonth", "expirationYear", "accessKey", "authCred", "salt",
            // телефоны
            "telefon", "phones")) {
            assertThat(rules.ruleFor(name)).as("FINDING no rule: " + name).isNull();
        }
    }

    @Test
    void findingTrailingDigitRuleIsPartial() {
        // README: «номер на конце имени поля не меняет правило». Работает только для HIDE_LAST/TAIL_LAST по последнему слову
        assertThat(rules.ruleFor("EXPIRY1")).isEqualTo(MaskingRules.Rule.HIDE);
        assertThat(rules.ruleFor("PHONE2")).isEqualTo(MaskingRules.Rule.TAIL);
        for (String name : List.of("EXP_DATE1", "BIRTH_DATE2", "CARD_NUMBER2", "cardNumber2", "firstName2", "PERSON_CODE2",
            "address1", "ADDRESS2")) {
            assertThat(rules.ruleFor(name)).as("FINDING trailing digit ignored: " + name).isNull();
        }
    }

    @Test
    void findingThreeDigitCardSecretsUnderGenericNames() {
        // securityCode — правило DIGITS (4–8 цифр): CVV2 из 3 цифр проходит как есть
        assertThat(rules.ruleFor("securityCode")).isEqualTo(MaskingRules.Rule.DIGITS);
        assertThat(json("{\"securityCode\":\"739\"}")).as("FINDING").contains("739");
        assertThat(json("{\"securityCode\":739}")).as("FINDING").contains("739");
        for (String name : List.of("csc", "cvn", "cid", "card_verification_value", "cardCode", "secureCode")) {
            assertThat(rules.ruleFor(name)).as("FINDING: " + name).isNull();
            assertThat(json("{\"" + name + "\":\"739\"}")).as("FINDING: " + name).contains("739");
        }
        // для сравнения: явные cvv/cvc и cardSecurityCode скрываются
        assertThat(json("{\"cvv2\":\"739\",\"cardSecurityCode\":\"739\"}")).doesNotContain("739");
    }

    @Test
    void findingProcessingNamesNotCovered() {
        // Типичные поля клиента в Tieto CMS (уточнить по WSDL банка): M_NAME — отчество, E_MAILS — почта
        assertThat(rules.ruleFor("M_NAME")).as("FINDING").isNull();
        assertThat(rules.ruleFor("E_MAILS")).as("FINDING").isNull();
        assertThat(rules.ruleFor("R_E_MAILS")).as("FINDING").isNull();
        String xml = settings.renderBody(("<row><M_NAME>Ivanovich</M_NAME><E_MAILS>ivan@mail.uz</E_MAILS></row>")
            .getBytes(StandardCharsets.UTF_8), "text/xml");
        assertThat(xml).as("FINDING").contains("Ivanovich").contains("ivan@mail.uz");
    }

    @Test
    void findingOverMaskingOfBusinessFields() {
        // «serial no» в конце имени скрывает серийные номера терминалов и устройств — нужны при разборе инцидентов
        for (String name : List.of("terminalSerialNo", "posSerialNo", "deviceSerialNo", "cardSerialNo")) {
            assertThat(rules.ruleFor(name)).as("FINDING over-mask: " + name).isEqualTo(MaskingRules.Rule.HIDE);
        }
        // PCODE в ISO 8583-интеграциях — processing code (DE3), а не персональный код
        assertThat(rules.ruleFor("PCODE")).as("FINDING over-mask").isEqualTo(MaskingRules.Rule.HIDE);
        // «card» как квалификатор имени: cardName в сервисах банка — название/псевдоним карты
        assertThat(rules.ruleFor("cardName")).as("FINDING over-mask").isEqualTo(MaskingRules.Rule.NAME);
        // старое: authorization* целиком скрывается (ответ процессинга об авторизации)
        assertThat(rules.ruleFor("authorizationResponse")).isEqualTo(MaskingRules.Rule.HIDE);
        assertThat(rules.ruleFor("authorizationCode")).isEqualTo(MaskingRules.Rule.HIDE);
    }

    @Test
    void textMaskerOnCvvLikeKeys() {
        String masked = LogTextMasker.mask("securityCode=739 csc=739 cvv2=739");
        System.err.println("PROBE text: " + masked);
        assertThat(masked).as("FINDING: securityCode=739 в тексте").contains("securityCode=739");
        assertThat(masked).as("FINDING: csc=739 в тексте").contains("csc=739");
        assertThat(masked).doesNotContain("cvv2=739");
    }
}
