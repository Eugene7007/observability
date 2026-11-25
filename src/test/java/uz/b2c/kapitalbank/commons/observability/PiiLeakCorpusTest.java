package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Защитный корпус утечек ПДн. Тела с контрольными значениями (номер карты, CVV, ПИНФЛ, телефон, паспорт, пароль,
 * track2, OTP) в JSON, XML, тексте и форме проходят через маскер целиком и обрезанными на каждом смещении, а также
 * через фильтр при ошибке. Тест падает, если в выводе есть контрольное значение или любая последовательность из
 * 13–19 цифр с верной контрольной суммой. Новые форматы и правила маскера добавляются сюда вместе с кодом.
 */
class PiiLeakCorpusTest {

    private static final String PAN = "8600123456781233";
    private static final String VISA = "4111111111111111";
    private static final String[] MARKERS = {
        PAN, VISA, "31234567890123", "998901234567", "901234567", "AA1234567", "S3cr3t!x", "25121011234567890",
        "482913", "Qwerty-Secret", "eyJhbGciOiJIUzI1NiJ9"};

    private static final String JSON = "{\"cardNumber\":\"" + PAN + "\",\"cvv\":\"739\",\"pinfl\":\"31234567890123\","
        + "\"phone\":\"998901234567\",\"passport\":\"AA1234567\",\"password\":\"S3cr3t!x\","
        + "\"track2\":\"" + PAN + "=25121011234567890\",\"otp\":\"482913\",\"comment\":\"pay " + VISA + " now\","
        + "\"nested\":\"{\\\"pan\\\":\\\"" + PAN + "\\\"}\",\"amount\":" + VISA + ",\"token\":\"eyJhbGciOiJIUzI1NiJ9\","
        + "\"params\":[{\"name\":\"secret\",\"value\":\"Qwerty-Secret\"},{\"value\":\"S3cr3t!x\",\"key\":\"password\"}],"
        + "\"holder\":{\"name\":\"IVANOV IVAN\"},\"ref\":\"N" + PAN + "\",\"ref2\":\"" + PAN + "_x\","
        + "\"note\":\"card " + VISA + "phone\"}";

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Envelope><Body><Pay>"
        + "<!-- " + PAN + " --><?audit " + VISA + "?><cardNumber>" + PAN + "</cardNumber><cvv>739</cvv>"
        + "<pinfl>31234567890123</pinfl><phone>998901234567</phone><doc passport=\"AA1234567\"/>"
        + "<param name=\"password\">S3cr3t!x</param><Property Key=\"secret\" Value=\"Qwerty-Secret\"/>"
        + "<track2>" + PAN + "=25121011234567890</track2><otp>482913</otp><comment><![CDATA[pay " + VISA + "]]></comment>"
        + "<split>86001234<b/>56781233</split><split2>8600123456<!-- x -->781233</split2>"
        + "<token>eyJhbGciOiJIUzI1NiJ9</token></Pay></Body></Envelope>";

    /** SOAP-ответ процессинга в духе Tieto/Humo: поля карты и клиента по их именам. */
    private static final String PROCESSING = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><soapenv:Envelope "
        + "xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body><queryCardInfoResponse><row>"
        + "<CARD>" + PAN + "</CARD><CARD_NUMBER>" + PAN + "</CARD_NUMBER><primaryAccountNumber>" + VISA
        + "</primaryAccountNumber><EXPIRY>2712</EXPIRY><EXPIRY1>2712</EXPIRY1><CARD_ACCT>20208000900123456001</CARD_ACCT>"
        + "<F_NAMES>Ivan</F_NAMES><SURNAME>Ivanov</SURNAME><ABVR_NAME>IVANOV I</ABVR_NAME><CARD_NAME>IVAN IVANOV</CARD_NAME>"
        + "<PERSON_CODE>31234567890123</PERSON_CODE><R_PCODE>31234567890123</R_PCODE><B_DATE>1990-01-31</B_DATE>"
        + "<R_STREET>Amir Temur 15</R_STREET><SERIAL_NO>AA1234567</SERIAL_NO><ID_CARD>AA1234567</ID_CARD>"
        + "<MB_PHONE>998901234567</MB_PHONE><encryptedPin>9F1A2B3C4D5E</encryptedPin>"
        + "<cardSecurityCode>739</cardSecurityCode><TRAN_AMT>150000</TRAN_AMT><RESPONSE_CODE>0</RESPONSE_CODE>"
        + "</row></queryCardInfoResponse></soapenv:Body></soapenv:Envelope>";

    private static final String[] PROCESSING_MARKERS = {
        "20208000900123456001", "Ivanov", "IVANOV", "IVAN IVANOV", "1990-01-31", "Amir Temur", "2712", "9F1A2B3C4D5E", ">739<"};

    @Test
    void processingSoapDoesNotLeak() {
        String rendered = settings.renderBody(bytes(PROCESSING), "text/xml; charset=utf-8");
        assertNoLeak("processing", rendered);
        for (String marker : PROCESSING_MARKERS) {
            assertThat(rendered).as("processing leaks %s: %s", marker, rendered).doesNotContain(marker);
        }
        // деловые поля остаются для разбора
        assertThat(rendered).contains("<TRAN_AMT>150000</TRAN_AMT>").contains("<RESPONSE_CODE>0</RESPONSE_CODE>");
        XmlBodyMasker xml = new XmlBodyMasker(MaskingRules.base());
        byte[] data = bytes(PROCESSING);
        for (int cut = 1; cut < data.length; cut++) {
            assertNoLeak("processing@" + cut, xml.mask(data, cut, true));
        }
    }

    private static final String TEXT = "cardNumber=" + PAN + " cvv=739 pinfl=31234567890123 phone=998901234567 "
        + "passport=AA1234567 password=S3cr3t!x otp=482913 note: " + VISA + " token=eyJhbGciOiJIUzI1NiJ9 ref=N" + PAN;

    private final HttpLogSettings settings = new HttpLogSettings(new HttpLogProperties());

    @Test
    void fullBodiesDoNotLeak() {
        assertNoLeak("json", settings.renderBody(bytes(JSON), "application/json"));
        assertNoLeak("xml", settings.renderBody(bytes(XML), "application/soap+xml; charset=utf-8"));
        assertNoLeak("text", settings.renderBody(bytes(TEXT), "text/plain"));
        assertNoLeak("unknown", settings.renderBody(bytes(JSON), null));
    }

    @Test
    void bodiesTruncatedAtEveryOffsetDoNotLeak() {
        JsonBodyMasker json = new JsonBodyMasker(MaskingRules.base());
        XmlBodyMasker xml = new XmlBodyMasker(MaskingRules.base());
        byte[] jsonBytes = bytes(JSON);
        byte[] xmlBytes = bytes(XML);
        for (int cut = 1; cut < jsonBytes.length; cut++) {
            assertNoLeak("json@" + cut, json.mask(jsonBytes, cut, true));
        }
        for (int cut = 1; cut < xmlBytes.length; cut++) {
            assertNoLeak("xml@" + cut, xml.mask(xmlBytes, cut, true));
        }
        byte[] text = bytes(TEXT);
        for (int cut = 1; cut < text.length; cut++) {
            assertNoLeak("text@" + cut, settings.maskText(text, cut, true, null));
        }
    }

    @Test
    void errorRecordsDoNotLeak() throws Exception {
        String[][] cases = {{JSON, "application/json"}, {XML, "text/xml"}, {TEXT, "text/plain"}};
        for (String[] c : cases) {
            HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/cards/" + PAN + "/pay");
            request.setContentType(c[1]);
            request.setContent(bytes(c[0]));
            new HttpServerLoggingFilter(settings, sink).doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                ((HttpServletResponse) res).setStatus(400);
                res.setContentType(c[1]);
                res.getOutputStream().write(bytes(c[0]));
            });
            assertThat(sink.events).hasSize(1);
            HttpLogMessage message = (HttpLogMessage) sink.events.get(0)[1];
            assertThat(message.get("http.request.body.content")).isNotNull();
            assertNoLeak(c[1], String.valueOf(message.getFormattedMessage()) + message.getData());
        }
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static final Pattern DIGITS = Pattern.compile("\\d{13,19}");

    static void assertNoLeak(final String label, final String output) {
        if (output == null) {
            return;
        }
        // номер, разрезанный разметкой, проверяется и в тексте без тегов
        String withoutTags = output.replaceAll("<[^>]*>", "");
        if (!withoutTags.equals(output)) {
            assertNoLeak(label + " (без тегов)", withoutTags);
        }
        for (String marker : MARKERS) {
            assertThat(output).as("%s leaks %s: %s", label, marker, output).doesNotContain(marker);
        }
        List<String> pans = new ArrayList<>();
        Matcher matcher = DIGITS.matcher(output);
        while (matcher.find()) {
            if (LogTextMasker.isPan(matcher.group())) {
                pans.add(matcher.group());
            }
        }
        assertThat(pans).as("%s leaks a card number: %s", label, output).isEmpty();
    }
}
