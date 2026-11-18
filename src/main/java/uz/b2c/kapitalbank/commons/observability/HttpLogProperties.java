package uz.b2c.kapitalbank.commons.observability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Настройки логирования HTTP-обменов (входящих запросов и исходящих вызовов RestTemplate),
 * префикс {@code lib.observability.http-log}. Списки путей и заголовков в YAML заменяют значения по умолчанию целиком;
 * списки маскирования, наоборот, только добавляют поля к базовому набору банка.
 */
public class HttpLogProperties {

    /**
     * Тела успешных обменов (статус ниже 400). Тела при 4xx, 5xx и исключении пишутся всегда и этим не отключаются,
     * маскирование ПДн работает при любом значении. Переключается каждым сервисом переменной
     * {@code LIB_OBSERVABILITY_HTTP_LOG_BODIES} без изменений кода.
     */
    public enum Bodies {
        /** Тела только у ошибок: 4xx, 5xx, исключение. Значение по умолчанию. */
        ON_ERROR,
        /** Тела у всех обменов, включая успешные. Для отладки: дороже по CPU и объёму логов. */
        ALWAYS
    }

    /** Нижняя граница {@code max-body-bytes}: меньшее значение заменяется ею, чтобы у ошибок оставалось тело. */
    public static final int MIN_BODY_BYTES = 256;

    /** Верхняя граница {@code max-body-bytes}: защищает память пода, сообщения Kafka и хранилище логов. */
    public static final int MAX_BODY_BYTES = 64 * 1024;

    /**
     * Включено ли логирование HTTP-обменов. Выключение убирает фильтр и интерцептор целиком.
     */
    private boolean enabled = true;

    /**
     * Писать ли тела успешных обменов. По умолчанию — нет, тела только у ошибок.
     */
    private Bodies bodies = Bodies.ON_ERROR;

    /**
     * Сколько байт тела запроса и ответа попадает в лог; полный размер пишется отдельно. Допустимо от
     * {@link #MIN_BODY_BYTES} до {@link #MAX_BODY_BYTES}: значение вне границ заменяется ближайшей с WARN при старте.
     */
    private int maxBodyBytes = 4096;

    /**
     * Пути (Ant-шаблоны), обмены по которым не логируются вообще.
     */
    private List<String> excludePaths = new ArrayList<>(List.of(
        "/actuator/**", "/swagger/**", "/v3/api-docs/**", "/swagger-ui/**",
        // как в прежней библиотеке: обмены с одноразовыми кодами и токенами не логируются вовсе
        "/**/send-otp", "/**/send-sms", "/**/realms/*/protocol/**", "/**/sms/send-async/**"));

    /**
     * Пути (Ant-шаблоны), для которых пишутся тела успешных обменов при {@code bodies=ALWAYS}. По умолчанию — все
     * пути; сервис может сузить отладку, например до {@code /api/v1/payments/**}. На тела ошибок не влияет.
     */
    private List<String> bodyPaths = new ArrayList<>(List.of("/**"));

    /**
     * Заголовки, которые разрешено писать в лог (без учёта регистра). Всё остальное не логируется.
     */
    private Set<String> allowedHeaders = new LinkedHashSet<>(List.of(
        "content-type", "content-length", "accept", "user-agent",
        "x-request-id", "correlationid", "clientdeviceid", "deviceid", "traceparent"
    ));

    private Masking masking = new Masking();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public Bodies getBodies() {
        return bodies;
    }

    public void setBodies(final Bodies bodies) {
        this.bodies = bodies;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(final int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(final List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    public List<String> getBodyPaths() {
        return bodyPaths;
    }

    public void setBodyPaths(final List<String> bodyPaths) {
        this.bodyPaths = bodyPaths;
    }

    public Set<String> getAllowedHeaders() {
        return allowedHeaders;
    }

    public void setAllowedHeaders(final Set<String> allowedHeaders) {
        this.allowedHeaders = allowedHeaders;
    }

    public Masking getMasking() {
        return masking;
    }

    public void setMasking(final Masking masking) {
        this.masking = masking;
    }

    /**
     * Поля, которые команда маскирует дополнительно к базовым правилам банка ({@link MaskingRules}).
     * Базовые правила из конфигурации не убираются и не ослабляются: поля команды применяются только к именам,
     * которые база не распознала. Имена сравниваются без учёта регистра и разделителей (card_number = cardNumber).
     * Имена сравниваются без учёта регистра; элемент с точкой (например, {@code otp.code})
     * сравнивается с концом пути к полю в JSON.
     */
    public static class Masking {

        /** Номер карты: первые 6 и последние 4 цифры. */
        private Set<String> pan = new LinkedHashSet<>();

        /** Телефон: только последние 4 цифры. */
        private Set<String> phone = new LinkedHashSet<>();

        /** Номер счёта: только последние 4 цифры. */
        private Set<String> account = new LinkedHashSet<>();

        /** ФИО: только первая буква. */
        private Set<String> name = new LinkedHashSet<>();

        /** Скрываются полностью (любое значение, включая вложенные объекты). */
        private Set<String> hide = new LinkedHashSet<>();

        /** Коды подтверждения: скрываются строки из цифр, остальные значения (коды ошибок) остаются. */
        private Set<String> codes = new LinkedHashSet<>();

        public Set<String> getPan() {
            return pan;
        }

        public void setPan(final Set<String> pan) {
            this.pan = pan;
        }

        public Set<String> getPhone() {
            return phone;
        }

        public void setPhone(final Set<String> phone) {
            this.phone = phone;
        }

        public Set<String> getAccount() {
            return account;
        }

        public void setAccount(final Set<String> account) {
            this.account = account;
        }

        public Set<String> getName() {
            return name;
        }

        public void setName(final Set<String> name) {
            this.name = name;
        }

        public Set<String> getHide() {
            return hide;
        }

        public void setHide(final Set<String> hide) {
            this.hide = hide;
        }

        public Set<String> getCodes() {
            return codes;
        }

        public void setCodes(final Set<String> codes) {
            this.codes = codes;
        }
    }
}
