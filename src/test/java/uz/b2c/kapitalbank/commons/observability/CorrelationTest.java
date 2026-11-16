package uz.b2c.kapitalbank.commons.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void incomingIdsGoToMdcAndResponseAndAreClearedAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/x");
        request.addHeader(Correlation.CORRELATION_ID, "c-123");
        request.addHeader(Correlation.CLIENT_DEVICE_ID, "device-7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        new CorrelationFilter().doFilter(request, response,
            (req, res) -> seen.set(MDC.get(Correlation.CORRELATION_ID) + "/" + MDC.get(Correlation.CLIENT_DEVICE_ID)));

        assertThat(seen.get()).isEqualTo("c-123/device-7");
        assertThat(response.getHeader(Correlation.CORRELATION_ID)).isEqualTo("c-123");
        assertThat(response.getHeader(Correlation.CLIENT_DEVICE_ID)).isEqualTo("device-7");
        assertThat(MDC.get(Correlation.CORRELATION_ID)).isNull();
    }

    @Test
    void missingIdIsGeneratedAndForeignFormatsAreKeptWithoutControlChars() throws Exception {
        MockHttpServletRequest generated = new MockHttpServletRequest("GET", "/internal/v1/x");
        MockHttpServletResponse generatedResponse = new MockHttpServletResponse();
        new CorrelationFilter().doFilter(generated, generatedResponse, (req, res) -> { });

        MockHttpServletRequest foreign = new MockHttpServletRequest("GET", "/internal/v1/x");
        foreign.addHeader(Correlation.CORRELATION_ID, "aGVsbG8+d29ybGQ=\nFAKE LOG LINE");
        foreign.addHeader(Correlation.CLIENT_DEVICE_ID, "iPhone14,2");
        MockHttpServletResponse foreignResponse = new MockHttpServletResponse();
        new CorrelationFilter().doFilter(foreign, foreignResponse, (req, res) -> { });

        String id = generatedResponse.getHeader(Correlation.CORRELATION_ID);
        assertThat(id).matches("[0-9a-f\\-]{36}");
        assertThat(generatedResponse.getHeader(Correlation.CLIENT_DEVICE_ID)).isEqualTo(id);
        assertThat(foreignResponse.getHeader(Correlation.CORRELATION_ID)).isEqualTo("aGVsbG8+d29ybGQ=FAKE LOG LINE");
        assertThat(foreignResponse.getHeader(Correlation.CLIENT_DEVICE_ID)).isEqualTo("iPhone14,2");
    }

    @Test
    void userGuidAndDeviceIdGoToMdcAndToOutgoingCalls() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/x");
        request.addHeader(Correlation.USER_GUID_HEADER, "u-1");
        request.addHeader(Correlation.DEVICE_ID_HEADER, "d-1");
        MockClientHttpRequest outgoing = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://bin-service/x"));
        AtomicReference<String> seen = new AtomicReference<>();

        new CorrelationFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            seen.set(MDC.get(Correlation.USER_GUID) + "/" + MDC.get(Correlation.DEVICE_ID));
            new CorrelationInterceptor().intercept(outgoing, new byte[0],
                (r, b) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        });

        assertThat(seen.get()).isEqualTo("u-1/d-1");
        assertThat(outgoing.getHeaders().getFirst(Correlation.USER_GUID_HEADER)).isEqualTo("u-1");
        assertThat(outgoing.getHeaders().getFirst(Correlation.DEVICE_ID_HEADER)).isEqualTo("d-1");
        assertThat(MDC.get(Correlation.USER_GUID)).isNull();
    }

    @Test
    void outgoingCallCarriesIdsFromMdcOrGeneratesNew() throws Exception {
        CorrelationInterceptor interceptor = new CorrelationInterceptor();
        MockClientHttpRequest inRequest = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://sv-gate/api"));
        MDC.put(Correlation.CORRELATION_ID, "c-123");
        interceptor.intercept(inRequest, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        MDC.clear();
        MockClientHttpRequest background = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://sv-gate/api"));
        interceptor.intercept(background, new byte[0], (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));

        assertThat(inRequest.getHeaders().getFirst(Correlation.CORRELATION_ID)).isEqualTo("c-123");
        assertThat(inRequest.getHeaders().getFirst(Correlation.CLIENT_DEVICE_ID)).isEqualTo("c-123");
        assertThat(background.getHeaders().getFirst(Correlation.CORRELATION_ID)).isNotBlank();
    }

    @Test
    void customizerAddsBothInterceptorsOnceInRightOrder() {
        CorrelationInterceptor correlation = new CorrelationInterceptor();
        HttpClientLoggingInterceptor logging =
            new HttpClientLoggingInterceptor(new HttpLogSettings(new HttpLogProperties()));
        ClientHttpRequestInterceptor existing = (req, body, exec) -> exec.execute(req, body);
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(existing);
        RestTemplateLoggingInstaller installer = new RestTemplateLoggingInstaller(correlation, logging);

        installer.customize(restTemplate);
        installer.customize(restTemplate);

        assertThat(restTemplate.getInterceptors()).containsExactly(correlation, existing, logging);
    }
}
