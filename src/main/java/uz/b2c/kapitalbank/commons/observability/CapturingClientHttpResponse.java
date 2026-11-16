package uz.b2c.kapitalbank.commons.observability;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * Ответ внешней системы, который копирует первые байты тела по мере чтения приложением
 * и вызывает запись в лог один раз — при закрытии ответа (RestTemplate закрывает его всегда).
 */
final class CapturingClientHttpResponse implements ClientHttpResponse {

    private final ClientHttpResponse delegate;
    private final BodyCapture capture;
    private final Runnable onClose;
    private InputStream body;
    private boolean logged;

    CapturingClientHttpResponse(final ClientHttpResponse delegate, final BodyCapture capture, final Runnable onClose) {
        this.delegate = delegate;
        this.capture = capture;
        this.onClose = onClose;
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
        return delegate.getStatusCode();
    }

    @Override
    @Deprecated
    @SuppressWarnings("removal")
    public int getRawStatusCode() throws IOException {
        return delegate.getStatusCode().value();
    }

    @Override
    public String getStatusText() throws IOException {
        return delegate.getStatusText();
    }

    @Override
    public HttpHeaders getHeaders() {
        return delegate.getHeaders();
    }

    @Override
    public InputStream getBody() throws IOException {
        if (body == null) {
            body = new TeeInputStream(delegate.getBody(), capture);
        }
        return body;
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            if (!logged) {
                logged = true;
                onClose.run();
            }
        }
    }

    private static final class TeeInputStream extends InputStream {

        private final InputStream delegate;
        private final BodyCapture capture;

        TeeInputStream(final InputStream delegate, final BodyCapture capture) {
            this.delegate = delegate;
            this.capture = capture;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                capture.write(b);
            }
            return b;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                capture.write(buffer, offset, read);
            }
            return read;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
