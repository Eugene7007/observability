package uz.b2c.kapitalbank.commons.observability;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpClientLoggingInterceptorTest {

    private static final String RESPONSE =
        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"pan\":\"8600510343218413\",\"fullName\":\"IVANOV\"}}";

    private final HttpServerLoggingFilterTest.RecordingSink sink = new HttpServerLoggingFilterTest.RecordingSink();

    private HttpClientLoggingInterceptor interceptor(final List<String> bodyPaths) {
        HttpLogProperties properties = new HttpLogProperties();
        // прежние тесты проверяют запись тел при любом статусе; режим ON_ERROR проверяется отдельно
        properties.setBodies(HttpLogProperties.Bodies.ALWAYS);
        properties.setBodyPaths(bodyPaths);
        return new HttpClientLoggingInterceptor(new HttpLogSettings(properties), sink);
    }

    private static MockClientHttpRequest request() {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://sv-gate:8080/api/jsonrpc?x=1"));
        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        request.getHeaders().setBasicAuth("user", "password");
        return request;
    }

    @Test
    void logsOnCloseWithMaskedBodiesAndWithoutCredentials() throws Exception {
        byte[] body = "{\"method\":\"cards.get\",\"params\":{\"expiry\":\"2812\"}}".getBytes(StandardCharsets.UTF_8);
        MockClientHttpResponse wire = new MockClientHttpResponse(RESPONSE.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        wire.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        ClientHttpRequestExecution execution = (req, b) -> wire;

        ClientHttpResponse response = interceptor(List.of("/**")).intercept(request(), body, execution);
        assertThat(sink.events).as("лог пишется при закрытии, когда тело уже прочитано").isEmpty();
        String read = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
        response.close();
        response.close();

        assertThat(read).isEqualTo(RESPONSE);
        HttpLogMessage message = sink.single(Level.INFO);
        assertThat(message.getFormattedMessage()).startsWith("HTTP out POST http://sv-gate:8080/api/jsonrpc -> 200 in ");
        assertThat(message.get("http.request.body.content")).isEqualTo("{\"method\":\"cards.get\",\"params\":{\"expiry\":\"***\"}}");
        assertThat(message.get("http.response.body.content"))
            .contains("\"pan\":\"860051******8413\"")
            .contains("\"fullName\":\"I***\"");
        assertThat(message.get("http.request.headers")).contains("Content-Type=application/json")
            .doesNotContain("Authorization").doesNotContain("Basic");
        assertThat(message.get("url.full")).doesNotContain("x=1");
    }

    @Test
    void withoutBodyPathsResponseIsNotWrapped() throws Exception {
        MockClientHttpResponse wire = new MockClientHttpResponse(new byte[0], HttpStatus.BAD_GATEWAY);
        ClientHttpResponse response = interceptor(List.of()).intercept(request(), new byte[0], (req, b) -> wire);

        assertThat(response).isSameAs(wire);
        HttpLogMessage message = sink.single(Level.WARN);
        assertThat(message.get("http.response.status_code")).isEqualTo("502");
        assertThat(message.containsKey("http.request.body.content")).isFalse();
    }

    @Test
    void panInOutgoingPathIsMasked() throws Exception {
        MockClientHttpRequest request =
            new MockClientHttpRequest(HttpMethod.GET, URI.create("http://bin-service/internal/v1/cards/8600510343218413/info"));
        MockClientHttpResponse wire = new MockClientHttpResponse(new byte[0], HttpStatus.OK);

        interceptor(List.of()).intercept(request, new byte[0], (req, b) -> wire);

        assertThat(sink.single(Level.INFO).get("url.full"))
            .isEqualTo("http://bin-service/internal/v1/cards/860051******8413/info");
    }

    @Test
    void warnLevelStillLogsTransportFailures() {
        HttpServerLoggingFilterTest.RecordingSink warnOnly = new HttpServerLoggingFilterTest.RecordingSink(Level.WARN);
        HttpClientLoggingInterceptor warnInterceptor =
            new HttpClientLoggingInterceptor(new HttpLogSettings(new HttpLogProperties()), warnOnly);

        assertThatThrownBy(() -> warnInterceptor.intercept(request(), new byte[0], (req, b) -> {
            throw new SocketTimeoutException("Read timed out");
        })).isInstanceOf(IOException.class);

        assertThat(warnOnly.single(Level.WARN).get("error.type")).isEqualTo("java.net.SocketTimeoutException");
    }

    @Test
    void transportFailureIsLoggedAndRethrown() {
        ClientHttpRequestExecution execution = (req, b) -> {
            throw new SocketTimeoutException("Read timed out");
        };

        assertThatThrownBy(() -> interceptor(List.of("/**")).intercept(request(), new byte[0], execution))
            .isInstanceOf(IOException.class);

        HttpLogMessage message = sink.single(Level.WARN);
        assertThat(message.get("error.type")).isEqualTo("java.net.SocketTimeoutException");
        assertThat(message.getFormattedMessage()).contains("FAILED (SocketTimeoutException)");
    }
}
