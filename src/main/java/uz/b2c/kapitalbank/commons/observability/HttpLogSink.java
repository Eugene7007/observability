package uz.b2c.kapitalbank.commons.observability;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Куда отправляется событие HTTP-обмена. В проде — логгер log4j2, в тестах — подмена.
 */
public interface HttpLogSink {

    /**
     * Включён ли уровень. Фильтр и интерцептор спрашивают дважды: WARN — писать ли вообще
     * (ошибки и 5xx), INFO — писать ли успешные обмены и захватывать ли тела.
     */
    boolean isEnabled(Level level);

    void log(Level level, HttpLogMessage message);

    static HttpLogSink logger(final String name) {
        Logger logger = LogManager.getLogger(name);
        return new HttpLogSink() {
            @Override
            public boolean isEnabled(final Level level) {
                return logger.isEnabled(level);
            }

            @Override
            public void log(final Level level, final HttpLogMessage message) {
                logger.log(level, message);
            }
        };
    }
}
