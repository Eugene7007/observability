package uz.b2c.kapitalbank.commons.observability;

/**
 * Собственные поля библиотеки — те, для которых в OpenTelemetry semantic conventions нет атрибута. По правилу
 * OpenTelemetry для своих атрибутов они не используют пространства имён стандарта и идут под префиксом компании
 * в виде обратного домена: {@value #NAMESPACE}. Все остальные поля записи называются строго по стандарту.
 */
public final class HttpLogFields {

    /** Префикс собственных полей: обратный домен kapitalbank.uz. */
    public static final String NAMESPACE = "uz.kapitalbank.";

    /** Длительность HTTP-обмена в миллисекундах. */
    public static final String DURATION_MS = NAMESPACE + "duration_ms";

    /** Бизнес-код ошибки из тела ответа 4xx/5xx: {@code code}, {@code errorCode}, {@code error_code}, {@code error.code}. */
    public static final String ERROR_CODE = NAMESPACE + "error.code";

    /**
     * Сколько байт тела запроса прочитало приложение (после распаковки, до обрезки для лога). В OpenTelemetry
     * {@code http.request.body.size} — байты по сети, поэтому это значение — своё поле.
     */
    public static final String REQUEST_BODY_APP_SIZE = NAMESPACE + "http.request.body.app_size";

    /** Сколько байт тела ответа записало (in) или прочитало (out) приложение, без сжатия. */
    public static final String RESPONSE_BODY_APP_SIZE = NAMESPACE + "http.response.body.app_size";

    /** Устройство клиента из заголовка {@code DeviceId}. {@code device.id} в OpenTelemetry означает другое. */
    public static final String DEVICE_ID = NAMESPACE + "device_id";

    /** Префикс полей MapMessage приложения: {@code log.info(new MapMessage<>().with("orderId", 42))}. */
    public static final String DATA_PREFIX = NAMESPACE + "data.";

    private HttpLogFields() {
    }
}
