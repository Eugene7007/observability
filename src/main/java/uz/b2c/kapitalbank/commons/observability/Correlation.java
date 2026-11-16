package uz.b2c.kapitalbank.commons.observability;

import java.util.UUID;

/**
 * Идентификаторы корреляции между сервисами. Имена заголовков и ключей MDC совпадают с прежней
 * observability-autoconfiguration, поэтому сервисы на старой и новой библиотеке передают их друг другу без потерь.
 */
public final class Correlation {

    public static final String CORRELATION_ID = "correlationId";
    public static final String CLIENT_DEVICE_ID = "clientDeviceId";

    /**
     * Заголовки, которые раньше уходили в baggage и оттуда в MDC под ключами {@link #USER_GUID} и {@link #DEVICE_ID}.
     */
    public static final String USER_GUID_HEADER = "UserGuid";
    public static final String DEVICE_ID_HEADER = "DeviceId";
    public static final String USER_GUID = "userGuid";
    public static final String DEVICE_ID = "deviceId";

    private static final int MAX_LENGTH = 128;

    private Correlation() {
    }

    /**
     * Значение из чужого заголовка попадает в MDC и в каждую строку лога. Формат идентификаторов у вызывающих
     * сервисов не навязываем (бывают base64, запятые и т.п.), но убираем управляющие символы, чтобы через заголовок
     * нельзя было вписать в лог поддельную строку, и ограничиваем длину.
     *
     * @return очищенное значение или null, если после очистки ничего не осталось
     */
    public static String sanitize(final String value) {
        String result = null;
        if (value != null) {
            StringBuilder clean = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
            for (int i = 0; i < value.length() && clean.length() < MAX_LENGTH; i++) {
                char c = value.charAt(i);
                if (!Character.isISOControl(c)) {
                    clean.append(c);
                }
            }
            String trimmed = clean.toString().trim();
            result = trimmed.isEmpty() ? null : trimmed;
        }
        return result;
    }

    public static String sanitizeOrNew(final String value) {
        String clean = sanitize(value);
        return clean != null ? clean : newId();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
