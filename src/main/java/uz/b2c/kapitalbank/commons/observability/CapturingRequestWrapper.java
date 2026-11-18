package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Отдаёт приложению тело запроса как есть и попутно запоминает первые байты для лога.
 * В отличие от прежнего CachedBodyHttpServletRequest, не читает всё тело в память заранее.
 */
final class CapturingRequestWrapper extends HttpServletRequestWrapper {

    private final BodyCapture capture;
    private ServletInputStream stream;
    private BufferedReader reader;

    CapturingRequestWrapper(final HttpServletRequest request, final int limit) {
        super(request);
        this.capture = new BodyCapture(limit);
    }

    BodyCapture capture() {
        return capture;
    }

    /**
     * Дочитывает тело, которое приложение не прочитало: запрос отклонён раньше (400 по заголовку, 404, 405).
     * Читается не больше лимита захвата и ещё один байт — чтобы знать, обрезано ли тело. Вызывается только
     * после обработки запроса, когда приложению тело уже не нужно. Читать тело заранее, до приложения, нельзя:
     * поток запроса читается один раз, и контроллер получил бы пустое тело.
     */
    void captureUnread() {
        if (capture.total() > 0 || capture.limit() == 0 || getContentLengthLong() <= 0) {
            return;
        }
        try {
            ServletInputStream in = getInputStream();
            int remaining = capture.limit() + 1;
            byte[] chunk = new byte[Math.min(remaining, 8192)];
            while (remaining > 0) {
                int read = in.read(chunk, 0, Math.min(chunk.length, remaining));
                if (read < 0) {
                    break;
                }
                remaining -= read;
            }
        } catch (IOException | IllegalStateException e) {
            // тело уже недоступно (например, контейнер разобрал его как параметры формы) — пишем без него
        }
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
        if (reader == null) {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
        return reader;
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
