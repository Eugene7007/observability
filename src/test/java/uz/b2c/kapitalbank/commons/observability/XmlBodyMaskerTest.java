package uz.b2c.kapitalbank.commons.observability;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class XmlBodyMaskerTest {

    private final XmlBodyMasker masker = new XmlBodyMasker(MaskingRules.base());

    @Test
    void masksSoapElementsAttributesAndText() {
        String soap = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
            + "<ns:Pay xmlns:ns=\"urn:pay\"><ns:cardNumber>8600123456781233</ns:cardNumber><ns:cvv>123</ns:cvv>"
            + "<ns:client><ns:name>Ivan Ivanov</ns:name></ns:client><ns:card pan=\"4111111111111111\"/>"
            + "<ns:comment>call +998901234567</ns:comment><ns:amount>100</ns:amount></ns:Pay></soap:Body></soap:Envelope>";

        String masked = mask(soap, false);

        assertThat(masked).doesNotContain("8600123456781233").doesNotContain(">123<").doesNotContain("Ivanov")
            .doesNotContain("4111111111111111").doesNotContain("901234567")
            .contains("860012******1233").contains("<ns:amount>100</ns:amount>");
    }

    @Test
    void truncatedXmlKeepsParsedPartOnly() {
        String xml = "<r><amount>100</amount><pan>8600123456";

        String masked = mask(xml, true);

        assertThat(masked).contains("<amount>100</amount>").doesNotContain("8600123456").endsWith(XmlBodyMasker.TRUNCATED_MARK);
    }

    @Test
    void externalEntitiesAreNotResolved() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><r>&x;</r>";

        assertThat(mask(xxe, false)).doesNotContain("root:");
    }

    @Test
    void teamFieldsApplyToXml() {
        HttpLogProperties.Masking masking = new HttpLogProperties.Masking();
        masking.setHide(Set.of("loyaltyId"));
        XmlBodyMasker withTeam = new XmlBodyMasker(masking);
        byte[] body = "<r><loyaltyId>L-777</loyaltyId></r>".getBytes(StandardCharsets.UTF_8);

        assertThat(withTeam.mask(body, body.length, false)).isEqualTo("<r><loyaltyId>***</loyaltyId></r>");
    }

    @Test
    void notXmlIsNotLogged() {
        assertThat(mask("not xml at all {pan:8600123456781233}", false)).startsWith("[unparseable body");
    }

    private String mask(final String xml, final boolean truncated) {
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return masker.mask(bytes, bytes.length, truncated);
    }
}
