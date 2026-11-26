package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Пишет ответ клиенту сразу, без буферизации всего тела, и попутно запоминает первые байты для лога.
 * В отличие от ContentCachingResponseWrapper, ответ не копится в памяти целиком до конца запроса.
 */
final class CapturingResponseWrapper extends HttpServletResponseWrapper {

    private final BodyCapture capture;
    private ServletOutputStream stream;
    private PrintWriter writer;
    private boolean errorSent;
    private long declaredLength = -1;

    CapturingResponseWrapper(final HttpServletResponse response, final int limit) {
        super(response);
        this.capture = new BodyCapture(limit);
    }

    BodyCapture capture() {
        return capture;
    }

    /** Content-Length, который объявило приложение, или -1: размер тела по сети для http.response.body.size. */
    long declaredLength() {
        return declaredLength;
    }

    @Override
    public void setContentLength(final int length) {
        declaredLength = length;
        super.setContentLength(length);
    }

    @Override
    public void setContentLengthLong(final long length) {
        declaredLength = length;
        super.setContentLengthLong(length);
    }

    @Override
    public void setHeader(final String name, final String value) {
        rememberLength(name, value);
        super.setHeader(name, value);
    }

    @Override
    public void addHeader(final String name, final String value) {
        rememberLength(name, value);
        super.addHeader(name, value);
    }

    @Override
    public void setIntHeader(final String name, final int value) {
        rememberLength(name, String.valueOf(value));
        super.setIntHeader(name, value);
    }

    @Override
    public void addIntHeader(final String name, final int value) {
        rememberLength(name, String.valueOf(value));
        super.addIntHeader(name, value);
    }

    private void rememberLength(final String name, final String value) {
        if ("Content-Length".equalsIgnoreCase(name) && value != null) {
            try {
                declaredLength = Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                declaredLength = -1;
            }
        }
    }

    /** Ответ отдан через sendError: тело напишет страница ошибки (в Spring Boot — /error) уже после цепочки. */
    boolean errorSent() {
        return errorSent;
    }

    @Override
    public void sendError(final int status) throws IOException {
        errorSent = true;
        super.sendError(status);
    }

    @Override
    public void sendError(final int status, final String message) throws IOException {
        errorSent = true;
        super.sendError(status, message);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (stream == null) {
            stream = new TeeOutputStream(super.getOutputStream(), capture);
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset));
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        super.flushBuffer();
    }

    @Override
    public void resetBuffer() {
        super.resetBuffer();
        capture.reset();
    }

    @Override
    public void reset() {
        super.reset();
        capture.reset();
        declaredLength = -1;
    }

    /**
     * Дописывает в поток то, что осталось в буфере нашего PrintWriter. Вызывается фильтром в конце запроса,
     * иначе ответ, записанный через getWriter() без flush, потерялся бы.
     */
    void flushWriter() {
        if (writer != null) {
            writer.flush();
        }
    }

    private static final class TeeOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final BodyCapture capture;

        TeeOutputStream(final ServletOutputStream delegate, final BodyCapture capture) {
            this.delegate = delegate;
            this.capture = capture;
        }

        @Override
        public void write(final int b) throws IOException {
            delegate.write(b);
            capture.write(b);
        }

        @Override
        public void write(final byte[] buffer, final int offset, final int length) throws IOException {
            delegate.write(buffer, offset, length);
            capture.write(buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(final WriteListener listener) {
            delegate.setWriteListener(listener);
        }
    }
}
