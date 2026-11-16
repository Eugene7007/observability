package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Режим по умолчанию ON_ERROR: для путей из body-paths тела пишутся только в записи об ошибках.
 */
class BodyOnErrorTest {

    private static final byte[] REQUEST = "{\"pan\":\"8600510343218413\"}".getBytes(StandardCharsets.UTF_8);

    private final HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();

    private static HttpLogSettings settings() {
        HttpLogProperties properties = new HttpLogProperties();
        properties.setBodyPaths(List.of("/**"));
        assertThat(properties.getBodies()).isEqualTo(HttpLogProperties.Bodies.ON_ERROR);
        return new HttpLogSettings(properties);
    }

    private void serve(final int status) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/v1/hold");
        request.setContentType("application/json");
        request.setContent(REQUEST);
        FilterChain chain = (req, res) -> {
            req.getInputStream().readAllBytes();
            ((HttpServletResponse) res).setStatus(status);
            res.setContentType("application/json");
            res.getOutputStream().write("{\"error\":\"declined\"}".getBytes(StandardCharsets.UTF_8));
        };
        new HttpServerLoggingFilter(settings(), sink).doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    void successfulIncomingRequestHasNoBodies() throws Exception {
        serve(200);

        HttpLogMessage message = sink.single(Level.INFO);
        assertThat(message.containsKey("http.request.body.content")).isFalse();
        assertThat(message.containsKey("http.response.body.content")).isFalse();
    }

    @Test
    void failedIncomingRequestHasMaskedBodies() throws Exception {
        serve(422);

        HttpLogMessage message = sink.single(Level.INFO);
        assertThat(message.get("http.request.body.content")).isEqualTo("{\"pan\":\"860051******8413\"}");
        assertThat(message.get("http.response.body.content")).isEqualTo("{\"error\":\"declined\"}");
    }

    private ClientHttpResponse call(final HttpStatus status) throws Exception {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://sv-gate/api"));
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        MockClientHttpResponse wire = new MockClientHttpResponse("{\"code\":-1}".getBytes(StandardCharsets.UTF_8), status);
        wire.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        ClientHttpResponse response = new HttpClientLoggingInterceptor(settings(), sink)
            .intercept(request, REQUEST, (req, b) -> wire);
        response.getBody().readAllBytes();
        response.close();
        return response;
    }

    @Test
    void successfulOutgoingCallIsNotEvenWrapped() throws Exception {
        ClientHttpResponse response = call(HttpStatus.OK);

        assertThat(response).isInstanceOf(MockClientHttpResponse.class);
        assertThat(sink.single(Level.INFO).containsKey("http.request.body.content")).isFalse();
    }

    @Test
    void failedOutgoingCallHasBodies() throws Exception {
        call(HttpStatus.BAD_GATEWAY);

        HttpLogMessage message = sink.single(Level.WARN);
        assertThat(message.get("http.request.body.content")).isEqualTo("{\"pan\":\"860051******8413\"}");
        assertThat(message.get("http.response.body.content")).isEqualTo("{\"code\":-1}");
    }
}
