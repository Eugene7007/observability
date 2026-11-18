package uz.b2c.kapitalbank.commons.observability;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Запоминает начало тела запроса для лога и отдаёт приложению тело целиком, байт в байт.
 * <p>
 * До передачи запроса дальше по цепочке {@link #prefetch()} читает начало тела — не больше {@code limit + 1}
 * байт (лимит лога и ещё один байт, чтобы знать, обрезано ли тело). Приложение затем получает поток, который
 * сначала отдаёт эти байты, а потом продолжает читать из сокета. Так начало тела есть в логе при любом исходе,
 * даже если запрос отклонён до контроллера (404, 405, 415), а память на запрос ограничена лимитом.
 * <p>
 * Чтение блокирующее, как у самого приложения: клиент, который объявил тело и не шлёт его, держит поток до
 * тайм-аута соединения Tomcat. Это не новая уязвимость — так же он держит поток на любом POST, который читает
 * тело, — и закрывается на ingress, который буферизует тело запроса до отправки в сервис.
 * <p>
 * Тело формы {@code application/x-www-form-urlencoded} в POST (до {@link #FORM_LIMIT}) читается целиком, и обёртка
 * сама отдаёт обе его проекции: сырые байты через {@code getInputStream}/{@code getReader} и параметры через
 * {@code getParameter*} (параметры строки запроса, затем тела, как у контейнера). Так приложение, которое читает
 * форму сырой (например, для проверки подписи), получает те же байты, а в лог идут замаскированные параметры.
 * <p>
 * Multipart не читается заранее. Когда приложение (Spring) разбирает части, обёртка запоминает их имена, типы
 * и размеры для лога; если части никто не разбирал, в лог идут только тип и размер — библиотека не разбирает
 * multipart сама, чтобы не писать файлы на диск ради лога.
 */
final class CapturingRequestWrapper extends HttpServletRequestWrapper {

    /**
     * Форма больше этого размера заранее не читается: её параметры разбирает контейнер, а в лог идёт то, что
     * прочитало приложение. Предел держит память на запрос малой даже для формы из тысяч параметров.
     */
    static final int FORM_LIMIT = 64 * 1024;

    private final BodyCapture capture;
    /** Начало тела, прочитанное заранее; тот же массив — буфер захвата для лога (без второй копии). */
    private byte[] prefix = new byte[0];
    private int prefixLength;
    private Map<String, String[]> formBody;
    private boolean eof;
    private byte[] form;
    private Map<String, String[]> parameters;
    private String parts;
    private ServletInputStream stream;
    private BufferedReader reader;

    CapturingRequestWrapper(final HttpServletRequest request, final int limit) {
        super(request);
        this.capture = new BodyCapture(limit);
    }

    BodyCapture capture() {
        return capture;
    }

    /** Форма POST: в лог идут параметры. */
    boolean isFormPost() {
        return "POST".equalsIgnoreCase(getMethod()) && contentTypeStartsWith("application/x-www-form-urlencoded");
    }

    boolean isMultipart() {
        return contentTypeStartsWith("multipart/");
    }

    private boolean contentTypeStartsWith(final String type) {
        String contentType = getContentType();
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith(type);
    }

    private boolean hasBody() {
        String encoding = getHeader("Transfer-Encoding");
        return getContentLengthLong() > 0 || (encoding != null && encoding.toLowerCase(Locale.ROOT).contains("chunked"));
    }

    /**
     * Читает начало тела до передачи запроса приложению (форму — целиком). Приложению эти байты всё равно нужны,
     * поэтому для обычного запроса лишнего ожидания нет. Multipart и запросы без тела не трогаются. Ошибка чтения
     * не мешает запросу: приложение получит прочитанное начало, а дальше — ту же ошибку от контейнера.
     */
    void prefetch() {
        long length = getContentLengthLong();
        if (capture.limit() == 0 || isMultipart() || !hasBody()) {
            return;
        }
        boolean wholeForm = isFormPost() && length > 0 && length <= FORM_LIMIT;
        if (isFormPost() && !wholeForm) {
            // большая форма или форма без длины: тело разбирает контейнер, в лог — то, что прочитало приложение
            return;
        }
        int size = wholeForm ? (int) length + 1
            : (int) Math.min(capture.limit() + 1L, length > 0 ? length + 1 : capture.limit() + 1L);
        byte[] buffer = new byte[size];
        int filled = 0;
        try {
            ServletInputStream in = super.getInputStream();
            while (filled < buffer.length) {
                int read = in.read(buffer, filled, buffer.length - filled);
                if (read < 0) {
                    eof = true;
                    break;
                }
                filled += read;
            }
        } catch (IOException | IllegalStateException e) {
            // тело недоступно: приложение получит прочитанное начало, а дальше — ту же ошибку от контейнера
        }
        prefix = buffer;
        prefixLength = filled;
        capture.adopt(buffer, filled);
        if (wholeForm) {
            form = buffer;
        }
    }

    /**
     * Полный размер тела: объявленный Content-Length, а без него — прочитанное, если тело дочитано до конца;
     * {@code -1}, если размер неизвестен (chunked-тело, которое никто не дочитал).
     */
    long totalBytes() {
        long length = getContentLengthLong();
        if (length >= 0) {
            return Math.max(capture.total(), length);
        }
        return eof || !capture.truncated() ? capture.total() : -1;
    }

    /** Параметры формы для лога: из прочитанного тела, без разбора контейнером после ответа. */
    Map<String, String[]> formForLog() {
        if (form != null) {
            return formBody();
        }
        return capture.size() > 0 ? parseForm(capture.buffer(), capture.size()) : null;
    }

    /** Параметры из тела формы, разобранные один раз — и для приложения, и для лога. */
    private Map<String, String[]> formBody() {
        if (formBody == null) {
            formBody = parseForm(form, prefixLength);
        }
        return formBody;
    }

    /** Части multipart, если их разбирало приложение, иначе {@code null}. */
    String partsForLog() {
        return parts;
    }

    // ---------- тело ----------

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (stream == null) {
            stream = new ReplayInputStream(super.getInputStream());
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

    // ---------- параметры формы, прочитанной целиком ----------

    @Override
    public String getParameter(final String name) {
        if (form == null) {
            return super.getParameter(name);
        }
        String[] values = getParameterMap().get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (form == null) {
            return super.getParameterMap();
        }
        if (parameters == null) {
            // поток контейнера уже прочитан, поэтому контейнер разбирает только строку запроса
            Map<String, String[]> merged = new LinkedHashMap<>(super.getParameterMap());
            formBody().forEach((name, values) -> merged.merge(name, values, (a, b) -> {
                String[] joined = Arrays.copyOf(a, a.length + b.length);
                System.arraycopy(b, 0, joined, a.length, b.length);
                return joined;
            }));
            parameters = Collections.unmodifiableMap(merged);
        }
        return parameters;
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return form == null ? super.getParameterNames() : Collections.enumeration(getParameterMap().keySet());
    }

    @Override
    public String[] getParameterValues(final String name) {
        if (form == null) {
            return super.getParameterValues(name);
        }
        String[] values = getParameterMap().get(name);
        return values == null ? null : values.clone();
    }

    private Map<String, String[]> parseForm(final byte[] data, final int length) {
        String encoding = getCharacterEncoding();
        Charset charset = encoding != null && Charset.isSupported(encoding)
            ? Charset.forName(encoding) : StandardCharsets.ISO_8859_1;
        return HttpLogSettings.parseForm(data, length, charset);
    }

    // ---------- multipart: снимок частей, которые разобрало приложение ----------

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        Collection<Part> result = super.getParts();
        snapshot(result);
        return result;
    }

    @Override
    public Part getPart(final String name) throws IOException, ServletException {
        Part part = super.getPart(name);
        if (parts == null) {
            snapshot(super.getParts());
        }
        return part;
    }

    private void snapshot(final Collection<Part> all) {
        if (parts != null || all == null) {
            return;
        }
        StringBuilder result = new StringBuilder("[");
        for (Part part : all) {
            if (result.length() > 1) {
                result.append(", ");
            }
            // имя файла не пишется: в нём бывают ФИО
            result.append(LogTextMasker.maskOrPlaceholder(part.getName())).append(' ')
                .append(part.getContentType() == null ? "field" : part.getContentType())
                .append(' ').append(part.getSize()).append(" bytes");
            if (result.length() > capture.limit()) {
                result.append(", ...");
                break;
            }
        }
        parts = result.append(']').toString();
    }

    /** Сначала отдаёт заранее прочитанное начало, затем читает из контейнера и попутно копирует в лог. */
    private final class ReplayInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private int position;

        ReplayInputStream(final ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            if (position < prefixLength) {
                return prefix[position++] & 0xFF;
            }
            int b = delegate.read();
            if (b >= 0) {
                capture.write(b);
            } else {
                eof = true;
            }
            return b;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            if (position < prefixLength) {
                int count = Math.min(length, prefixLength - position);
                System.arraycopy(prefix, position, buffer, offset, count);
                position += count;
                return count;
            }
            if (eof) {
                return -1;
            }
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                capture.write(buffer, offset, read);
            } else if (read < 0) {
                eof = true;
            }
            return read;
        }

        @Override
        public int available() throws IOException {
            return prefixLength - position + (eof ? 0 : delegate.available());
        }

        @Override
        public boolean isFinished() {
            return position >= prefixLength && (eof || delegate.isFinished());
        }

        @Override
        public boolean isReady() {
            return position < prefixLength || eof || delegate.isReady();
        }

        /**
         * Неблокирующее чтение (Servlet async IO). Если всё тело уже в начале, прочитанном заранее, контейнер сразу
         * сообщит «данные кончились»; поэтому перед этим слушатель получает {@code onDataAvailable} и читает начало.
         */
        @Override
        public void setReadListener(final ReadListener listener) {
            delegate.setReadListener(new ReadListener() {
                @Override
                public void onDataAvailable() throws IOException {
                    listener.onDataAvailable();
                }

                @Override
                public void onAllDataRead() throws IOException {
                    if (position < prefixLength) {
                        listener.onDataAvailable();
                    }
                    listener.onAllDataRead();
                }

                @Override
                public void onError(final Throwable t) {
                    listener.onError(t);
                }
            });
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
