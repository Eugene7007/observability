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

    /** Префикс полей MapMessage приложения: {@code log.info(new MapMessage<>().with("orderId", 42))}. */
    public static final String DATA_PREFIX = NAMESPACE + "data.";

    private HttpLogFields() {
    }
}
