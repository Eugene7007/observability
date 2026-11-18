package uz.b2c.kapitalbank.commons.observability;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Находки третьего ревью по XML-маскеру: комментарии и PI, номер карты, разрезанный разметкой, формат «имя–значение»,
 * кодировка из Content-Type, коды результата, мусор в stderr.
 */
class XmlReviewFindingsTest {

    private final XmlBodyMasker masker = new XmlBodyMasker(MaskingRules.base());

    private String mask(final String xml) {
        byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        return masker.mask(data, data.length, false);
    }

    @Test
    void commentsAndProcessingInstructionsAreDropped() {
        String masked = mask("<r><!-- card 8600123456781233 exp 12/27 cvv=123 password=Qwerty1 -->"
            + "<?audit pan=8600123456781233?><amount>100</amount></r>");

        assertThat(masked).isEqualTo("<r><amount>100</amount></r>");
    }

    @Test
    void panSplitByMarkupIsMasked() {
        for (String xml : new String[] {
            "<r>8600123456<!--x-->781233</r>", "<r>86001234<?x?>56781233</r>", "<r>86001234<b/>56781233</r>",
            "<r><a>86001234</a><b>56781233</b></r>"}) {
            String masked = mask(xml);
            assertThat(masked).as(xml).doesNotContain("3456").doesNotContain("8600123456781233");
            assertThat(masked.replaceAll("<[^>]*>", "")).as(xml).isEqualTo("860012******1233");
        }
    }

    @Test
    void nameValueFormatIsMasked() {
        String masked = mask("<params><param name=\"cvv\">123</param><param name=\"amount\">100</param>"
            + "<Property Key=\"Password\" Value=\"Qwerty1\"/><param name=\"pinfl\">31234567890123</param>"
            + "<field key=\"cardNumber\">8600123456781233</field></params>");

        assertThat(masked).contains("<param name=\"cvv\">***</param>").contains("<param name=\"amount\">100</param>")
            .contains("Key=\"Password\"").doesNotContain("Qwerty1").doesNotContain("31234567890123")
            .contains("860012******1233");
    }

    @Test
    void charsetFromContentTypeIsUsedWhenBodyHasNoDeclaration() {
        String xml = "<r><comment>Привет</comment><pan>8600123456781233</pan></r>";
        byte[] data = xml.getBytes(Charset.forName("windows-1251"));

        String masked = masker.mask(data, data.length, false, "windows-1251");

        assertThat(masked).contains("Привет").contains("860012******1233");
    }

    @Test
    void resultCodesStayButOneTimeCodesAreHidden() {
        assertThat(mask("<r><code>0</code></r>")).isEqualTo("<r><code>0</code></r>");
        assertThat(mask("<r><code>INVALID_CARD</code></r>")).isEqualTo("<r><code>INVALID_CARD</code></r>");
        assertThat(mask("<r><code>123456</code></r>")).isEqualTo("<r><code>***</code></r>");
    }

    @Test
    void parserErrorsAreNotPrintedToStderr() {
        PrintStream original = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            byte[] broken = {'<', 'r', '>', (byte) 0xC3, (byte) 0x28, '<', '/', 'r', '>'};
            masker.mask(broken, broken.length, false);
            byte[] notXml = "plain text".getBytes(StandardCharsets.UTF_8);
            masker.mask(notXml, notXml.length, false);
        } finally {
            System.setErr(original);
        }
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void usesJdkStaxEvenIfAnotherImplementationIsOnClasspath() {
        assertThat(javax.xml.stream.XMLInputFactory.newDefaultFactory().getClass().getName()).startsWith("com.sun.");
    }
}
