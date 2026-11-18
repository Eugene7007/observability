package uz.b2c.kapitalbank.commons.observability;

import java.util.Arrays;

/**
 * Копит не больше {@code limit} первых байт тела и считает общий размер.
 * Само тело не буферизуется: данные проходят мимо, сюда попадает только начало для лога.
 */
final class BodyCapture {

    private static final int INITIAL = 512;

    private final int limit;
    private byte[] buffer;
    private int size;
    private boolean shared;
    private long total;

    BodyCapture(final int limit) {
        this.limit = Math.max(0, limit);
    }

    void write(final int b) {
        total++;
        if (size < limit) {
            ensure(size + 1);
            buffer[size++] = (byte) b;
        }
    }

    void write(final byte[] data, final int offset, final int length) {
        if (length <= 0) {
            return;
        }
        total += length;
        int toCopy = Math.min(length, limit - size);
        if (toCopy > 0) {
            ensure(size + toCopy);
            System.arraycopy(data, offset, buffer, size, toCopy);
            size += toCopy;
        }
    }

    private void ensure(final int required) {
        if (shared && required > size) {
            // общий с приложением массив не дописывается: дальше — своя копия
            buffer = Arrays.copyOf(buffer, Math.min(limit, Math.max(size * 2, required)));
            shared = false;
            return;
        }
        if (buffer == null) {
            buffer = new byte[Math.min(limit, Math.max(INITIAL, required))];
        } else if (required > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.min(limit, Math.max(buffer.length * 2, required)));
        }
    }

    /**
     * Берёт уже прочитанные байты как свой буфер, без копии: {@code filled} байт в начале {@code data}.
     * Массив не меняется (приложению из него же отдаётся начало тела).
     */
    void adopt(final byte[] data, final int filled) {
        if (size == 0 && filled > 0) {
            total += filled;
            buffer = data;
            size = Math.min(filled, limit);
            shared = true;
        } else {
            write(data, 0, filled);
        }
    }

    void reset() {
        size = 0;
        total = 0;
    }

    byte[] buffer() {
        return buffer == null ? new byte[0] : buffer;
    }

    int size() {
        return size;
    }

    long total() {
        return total;
    }

    int limit() {
        return limit;
    }

    boolean truncated() {
        return total > size;
    }
}
