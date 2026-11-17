package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Отдаёт приложению тело запроса как есть и попутно запоминает первые байты для лога.
 * В отличие от прежнего CachedBodyHttpServletRequest, не читает всё тело в память заранее.
 */
final class CapturingRequestWrapper extends HttpServletRequestWrapper {

    private final BodyCapture capture;
    private ServletInputStream stream;
    private BufferedReader reader;

    CapturingRequestWrapper(final HttpServletRequest request, final int limit) throws IOException {
        super(request);
        this.capture = new BodyCapture(limit);
        this.capture.write(request.getInputStream().readAllBytes(), 0, request.getContentLength());
    }

    BodyCapture capture() {
        return capture;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (stream == null) {
            stream = new TeeInputStream(super.getInputStream(), capture);
        }
        return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
//        if (reader == null) {
//            String encoding = getCharacterEncoding();
//            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
//            reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
//        }
//        return reader;

        return new BufferedReader(new InputStreamReader(this.getInputStream()));
    }

    private static final class TeeInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final BodyCapture capture;

        TeeInputStream(final ServletInputStream delegate, final BodyCapture capture) {
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
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(final ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
