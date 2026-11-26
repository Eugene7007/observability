# observability-starter

Общая библиотека наблюдаемости для Java-сервисов на Spring Boot 3.1–3.4 (servlet MVC). Она полностью заменяет старые `observability-autoconfiguration` и `log-autoconfiguration`: логи с маскированием ПДн, корреляцию, HTTP-логирование, а также то, что старая библиотека приносила попутно, — actuator, Prometheus, трассировку и Swagger. Подключение — замена одной зависимости в `build.gradle`, без изменений кода сервиса.

```groovy
// implementation 'uz.b2c.kapitalbank.observability:observability-autoconfiguration:0.0.72'   — убрать
implementation 'uz.b2c.kapitalbank.commons:observability-starter:<версия>'
```

Единственное исключение — deposits: он использует класс `TransactionRegistry` из старой библиотеки. Перед заменой этот класс нужно перенести в сам сервис (около десяти строк, метрика и теги прежние). В новую библиотеку бизнес-метрики не входят.

## Что даёт библиотека

**Логи.** JSON в stdout, имена полей — по OpenTelemetry semantic conventions: `service.name`, `service.version`, `deployment.environment.name`, `trace_id`, `span_id`, `user.id`, `http.*`, `url.*`, `exception.*` и др. В версиях 1.x — параллельно в прежний Kafka-топик, через ограниченную асинхронную очередь, которая не блокирует запросы.

**Корреляция.** `correlationId`, `clientDeviceId`, `UserGuid`, `DeviceId` из входящих заголовков попадают в MDC и передаются дальше: во все `RestTemplate` сервиса (бины, в том числе созданные через `new`, и шаблоны из `RestTemplateBuilder`) и в заголовки сообщений Kafka. Заголовки, которые сервис выставил сам, не перезаписываются. Консьюмер Kafka достаёт `correlationId` в MDC на время обработки записи и очищает после. Собственные Kafka-интерцепторы сервиса сохраняются: библиотека объединяет их со своими, а не заменяет. Имена заголовков совпадают со старой библиотекой.

**HTTP-логирование.** Одна запись на обмен: метод, путь, шаблон маршрута, статус, длительность, разрешённые заголовки и тела по правилам ниже. Уровень записи: успех — `INFO`, 4xx — `WARN`, 5xx и исключение — `ERROR`. Фильтры стоят в начале цепочки. Асинхронные контроллеры логируются по завершении, с итоговым статусом.

**Платформа** — то же, что давала старая библиотека, с теми же настройками по умолчанию:

| Что | Как |
| --- | --- |
| Actuator | Открыты `health,info,metrics,jvm,prometheus` (`MANAGEMENT_ENDPOINT_IDS`) |
| Prometheus | `/actuator/prometheus`, общий тег `application=<spring.application.name>` |
| Трассировка | Micrometer Tracing + Brave; `trace_id` и `span_id` в каждой строке лога; заголовки `UserGuid` и `DeviceId` передаются дальше и попадают в поля `user.id` и `device.id` |
| Swagger | springdoc 2.6.0, выключен, пока не задан `ENABLE_SWAGGER=true`; пути `/swagger/<сервис>/…`, адрес `SWAGGER_BASE_URL`; обязательный заголовок `Accept-Language` у каждого метода (значения — из enum `Language` общих библиотек), теги «Контроллер_метод»; CORS `*` для API, пока Swagger включён. Свой бин `OpenAPI` сервиса главнее |

Все эти значения добавляются с самым низким приоритетом, поэтому сервис переопределяет любое из них в своём `application.yml` или переменной окружения. Они действуют и при `LIB_OBSERVABILITY_ENABLED=false`.

## Тела запросов и ответов

Тела запроса и ответа при 4xx, 5xx и исключении пишутся **всегда**, у входящих запросов и у исходящих вызовов. Настройкой это не отключается: без тела ошибку не разобрать. Переключатель управляет только успешными обменами:

| `LIB_OBSERVABILITY_HTTP_LOG_BODIES` | Тела успешных обменов (ниже 400) |
| --- | --- |
| `ON_ERROR` (по умолчанию) | Не пишутся |
| `ALWAYS` | Пишутся — для отладки, потом вернуть обратно; `body-paths` сужает отладку до части путей |

Маскирование работает при любом значении. В каждой записи есть полный размер тела (`http.request.body.size`, `http.response.body.size`), а в лог попадают первые `max-body-bytes` байт: по умолчанию 4096, сервис меняет значение у себя в пределах от 256 байт до 64 КБ. Значение вне границ заменяется ближайшим, и при старте пишется предупреждение. Обрезанное тело помечается `...[truncated]`; число, оборванное на лимите, в лог не попадает.

Начало тела запроса читается до передачи запроса приложению, а приложение получает тело целиком, байт в байт. Поэтому тело есть в записи, даже если запрос отклонён до контроллера (404, 405, 415). Клиент, который объявил тело и не шлёт его, держит поток до тайм-аута соединения, как и на любом POST, читающем тело; от этого защищает ingress, буферизующий тело запроса. Начало тела читается до того, как сервис мог отклонить запрос, поэтому при `Expect: 100-continue` клиент начинает отправку тела и в случае отказа (у Tomcat это поведение по умолчанию, `continueResponseTiming=immediately`).

Форма POST до 2 МБ читается целиком: приложение получает и сырое тело (`getInputStream`, например для проверки подписи), и параметры (`getParameter`). Multipart библиотека сама не разбирает: в лог идут части, которые разобрало приложение, иначе тип и размер.

Если ответ — ошибка без тела (`sendError`: 404, 405, 415 из Spring; исключение), тело пишет страница ошибки Spring Boot уже после фильтра. Тогда запись ждёт её и включает её тело, с MDC исходного запроса. Отдельной записи о `/error` нет.

| Тип тела | Что в логе |
| --- | --- |
| JSON или без типа | Замаскированный JSON |
| XML, SOAP (`*/xml`, `*+xml`) | Замаскированный XML без комментариев и processing instructions; кодировка — из `Content-Type` или объявления |
| `text/*` | Текст через маскер строк лога |
| Форма POST | Параметры как замаскированный JSON |
| multipart | Имя, тип и размер каждой части, без содержимого и имён файлов |
| Остальное (бинарное) | Тип и размер |

Не логируются вовсе: actuator, Swagger и, как в старой библиотеке, отправка OTP и SMS и обмен с Keycloak.

## Маскирование ПДн

Выключателя у маскирования нет. Оно работает в трёх местах.

1. **Тела HTTP.** JSON и XML разбираются потоково. Поля, элементы и атрибуты маскируются по правилам. Формат «имя–значение» (`{"name":"password","value":"…"}`, `<param name="cvv">…</param>`) распознаётся по полям и атрибутам `name`, `key`, `field`, `param`. Каждое строковое значение дополнительно проходит разбор текста: вложенный JSON и XML, номера карт и телефоны. Число из 13–19 цифр с верной контрольной суммой маскируется как карта, в XML — и разрезанное тегами. Ключи-ПДн тоже маскируются. Число, на котором тело оборвалось при обрезке, скрывается.
2. **Каждая строка лога.** Сообщение, сообщение и стек исключения, поля `MapMessage` маскируются в самом формате вывода. Поэтому закрыто и то, что разработчик пишет сам: `log.info("ответ {}", dto)`, toString Lombok, JSON и XML в тексте, `HttpClientErrorException` с телом ответа. Если маскирование почему-то упало, пишется заглушка, а не исходный текст.
3. **Заголовки и URL.** `Authorization`, `Cookie`, `UserPinfl`, `UserPhone` и заголовки с `token`, `secret`, `password`, `pinfl`, `phone` в имени не пишутся никогда, даже если сервис добавил их в `allowed-headers`. В URL нет учётных данных и query. В пути длинные числа скрываются, номер карты маскируется.

**Базовые правила банка** распознают поле по словам имени (camelCase, `_`, `-`):

| Что | Примеры имён | Как |
| --- | --- | --- |
| Номер карты | `pan`, `cardNumber`, `card_number`, `senderCardNumber`, `cardNo`, `CREF_NO`, `hpan`, `processingKey`, `recipientProcessingKey`, `primaryAccountNumber` | первые 6 и последние 4 цифры |
| Телефон, счёт | `phone`, `phoneNumber`, `mobilePhone`, `mobile`, `mobileNumber`, `tel`, `msisdn`, `telefon`, `account`, `accountNumber`, `accNo`, `CARD_ACCT`, `iban` | последние 4 |
| ФИО | `firstName`, `firstname`, `lastName`, `middlename`, `fullName`, `holderName`, `cardHolder`, `EMBOS_NAME`, `embossedName`, `nameOnCard`, `beneficiaryName`, `payerName`, `surname`, `patronym`, `secondName`, `F_NAMES`, `M_NAME`, `ABVR_NAME`, `CARD_NAME`, `cardName`; `name` внутри объектов `client`, `card`, `holder`, `customer`, `recipient` и т. п. | первая буква |
| Почта | `email`, `E_MAILS` | первая буква и домен: `i***@mail.uz` |
| Секреты и документы | всё, где есть `cvv`, `cvc`, `pin`, `pinfl`, `pnfl`, `jshshir`, `stir`, `otp`, `password`, `secret`, `token`, `passport`, `address`, `track1`, `track2`, `pinBlock`, `pvv`, `cavv`, `icvv`; `birthDate`, `dateOfBirth`, `birthday`, `dob`, `expiry`, `expirationDate`, `expireDate`, `expMonth`, `expYear`, `validTo`, `validThru`, `documentNumber`, `docSeries`, `documentSeria`, `docIssueDate`, `docGivePlace`, `seriesNumber`, `nationalId`, `personalNumber`, `birthPlace`, `inn`, `tin`, `taxPayerCode`, `codeWord`, `smsCode`, `accessKey`, `salt`; код безопасности карты под любым именем: `cardSecurityCode`, `securityCode`, `secureCode`, `csc`, `cvn`; поля процессинга `PERSON_CODE`, `R_PCODE`, `B_DATE`, `R_STREET`, `ID_CARD`, `SERIAL_NO` | целиком |
| Коды подтверждения | `verificationCode`, `confirmCode`, `activationCode` и т. п. | 4–8 цифр скрываются в любом виде: строкой, числом, в тексте |
| Поле `code` | `code` | строка из цифр скрывается (OTP); число и текст остаются: это коды ошибок, `"CARD_EXPIRED"` |
| Адреса | `url`, `uri`, `link` | остаются схема, хост и путь; query скрывается, в пути маскируются длинные числа и карты |

Независимо от имён в любом тексте маскируются номера карт (13–19 цифр, в том числе группами через пробел, дефис или скобки, с проверкой Луна), мобильные номера `998XXXXXXXXX` и `+998 (XX) XXX-XX-XX` и `Bearer <токен>`. Поля команды только добавляются к базе: если имя узнали и база, и команда (по имени или по пути), применяется более строгое правило. Поэтому команда не может ослабить базу, а расширение базового словаря не ослабляет поле, которое команда скрыла. Поля команды, как и базовые, узнаются и с номером или суффиксом языка на конце: `loyaltyId` в `hide` скрывает и `loyaltyId2`, и `loyaltyIdEn`.

Номер на конце любого слова имени или отдельным словом и суффикс языка или алфавита не меняют правило: `EXPIRY1`, `EXP_DATE1` и `expiry_2` маскируются как срок карты, `address2` — как адрес, `pin_2` и `PIN_EN_1` — как PIN, `firstNameLatin`, `surnameCyr`, `lastNameEn` — как имя. Слово `authorization` в любом месте имени скрывает поле (`Authorization`, `authorizationKey`, `authorizationValue`, `authorizationCode`); открыты только деловые поля с последним словом из явного списка — дата, время, статус, id, тип (`authorizationDateTime`, `authorizationStatus`).

Словарь закреплён снимком `src/test/resources/masking-dictionary-snapshot.tsv`: правило базы для каждого из ~4200 имён — реальных полей всех сервисов банка и придуманных ревьюверами. Любое изменение словаря роняет `MaskingDictionarySnapshotTest`, ослабления выводятся первыми. Осознанное изменение — `./gradlew test -PupdateMaskingSnapshot`, и дифф снимка в PR показывает каждое изменённое поле. Новые имена в корпус можно добавлять строкой `имя<TAB>-`, затем обновить снимок. `SERIAL_NO` и `R_PCODE` процессинга скрываются только под этими точными именами, поэтому `terminalSerialNo` и `PCODE` (processing code ISO 8583) остаются. Имена полей процессингов Tieto и Humo входят в базовый словарь, их проверяет корпус `PiiLeakCorpusTest` на SOAP-ответе процессинга. SOAP-конверты обычно больше 4 КБ, поэтому адаптерам процессинга стоит поднять `LIB_OBSERVABILITY_HTTP_LOG_MAX_BODY_BYTES`, например до 16384.

```yaml
lib:
  observability:
    http-log:
      masking:
        pan: [virtualCard]
        phone: [contact]
        account: [loanRef]
        name: [beneficiary]
        hide: [loyaltyId, sms.text]   # элемент с точкой — путь в JSON-теле
        codes: [promoCode]
```

**Ограничения.**
- Примерно 10% случайных 13–19-значных идентификаторов, например epoch-millis, проходят проверку Луна и маскируются как карта. Это сознательный выбор в пользу лишнего маскирования.
- В тексте маскирование идёт с запасом. Например, скрываются `tokenType`, `phoneVerified`, `cookieConsent`.
- Не распознаются: местные номера без кода страны (`90 123 45 67`), поля внутри CDATA в XML, встреченном в строке лога (в XML-телах CDATA маскируется), и JSON с двойным экранированием. Значения MDC не маскируются.
- Формат «имя–значение» в JSON распознаётся, когда поле `name` идёт раньше `value`. В свободном тексте маскируется номер карты из track2, но не данные после `=`; поле `track2` скрывается целиком.
- Номер карты, разрезанный тегами XML, распознаётся по 13–19 цифрам подряд через теги, поэтому соседние числовые элементы на 16 цифр в сумме тоже маскируются.
- В текстовом формате для локального запуска стеки исключений не маскируются.

**Стоимость** после прогрева, на одном ядре:

| Строка | Время |
| --- | --- |
| Без пар «поле — значение» | 0,2 мкс |
| Lombok DTO | 1–3 мкс |
| JSON на 2,5 КБ в сообщении | 15–20 мкс |
| Стек на 150 фреймов | около 60 мкс |

Разбор линейный, без регулярных выражений, вложенность ограничена. Текст длиннее 32 КБ обрезается, оборванный номер скрывается.

## Формат логов (контракт)

Каждая запись — один JSON-объект на строку в stdout. На этот формат опираются сбор логов (Vector), хранилище, дашборды и алерты, поэтому он зафиксирован как контракт. Начиная с 1.0.0 формат меняется только добавлением полей. Переименование, удаление поля или смена его типа возможны только в новой мажорной версии и с предупреждением DevOps заранее.

**Имена полей — строго по OpenTelemetry semantic conventions.** Это правило: другие команды и инструменты (Vector, Grafana, коллектор OpenTelemetry) должны понимать поля без нашего словаря. Поля данных записи — время, уровень, сообщение, логгер, `trace_id`, `span_id` — соответствуют модели записи лога OpenTelemetry (Timestamp, SeverityText, Body, InstrumentationScope, TraceId, SpanId). Для них стандарт не задаёт плоских JSON-имён, отображение настраивается в Vector или коллекторе. Остальные поля — атрибуты из реестра OpenTelemetry. Если в стандарте атрибута нет, поле получает префикс компании `uz.kapitalbank.`. OpenTelemetry предписывает для своих атрибутов не занимать пространства имён стандарта, а ставить префикс обратного домена компании. Таких полей немного, все они перечислены в таблицах ниже. Новое поле сначала ищется в [реестре атрибутов](https://opentelemetry.io/docs/specs/semconv/registry/attributes/) и только при его отсутствии заводится под `uz.kapitalbank.`.

Контракт проверяют тесты `e2e/LogFormatContractTest` (режим STDOUT) и `e2e/LogFormatContractKafkaTest` (STDOUT_AND_KAFKA, как в 1.x на проде): stdout-запись описана в обеих конфигурациях, и проверяется каждая. Через настоящий log4j2 библиотеки пишется по записи каждого вида: обычная, с исключением, MapMessage приложения, входящий и исходящий HTTP, в том числе упавшие. Имена и типы полей сверяются с эталоном `src/test/resources/log-format-contract.json`, а значения — с правилами ниже: время в UTC, уровни, `error.type` и `server.port` по стандарту, отсутствие `null` и дубликатов ключей. Пропавшее или переименованное поле, сменившийся тип, новое поле или вид записи роняют сборку. Чтобы добавить поле, его вносят в эталон и в таблицы ниже в том же изменении.

Общие правила:
- Поле без значения не пишется вовсе, `null` в выводе не бывает. Например, `user.id` есть только в запросах после авторизации, `exception.message` — только у исключения с сообщением.
- Порядок полей в записи не входит в контракт: разбирайте по именам.
- `@timestamp` — UTC, ISO-8601 с миллисекундами: `2026-11-25T12:00:00.123Z`.
- `level` — `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`. HTTP-записи: успех — `INFO`, 4xx — `WARN`, 5xx и исключение — `ERROR`.
- Все текстовые значения уже замаскированы (раздел «Маскирование ПДн»).
- Тела обрезает сама библиотека до `max-body-bytes` с меткой `...[truncated]`, сообщения и стеки — до 32 КБ. Предел строки log4j2 (102400 символов) выше, с учётом роста тела при маскировании, и срабатывает только как страховка; пул буферов log4j ограничен 16 контекстами, поэтому предел не увеличивает удержание памяти с числом потоков.
- Поля `MapMessage`, которые пишет само приложение, выводятся с префиксом `uz.kapitalbank.data.` (`uz.kapitalbank.data.orderId`) и всегда строкой.
- Формат для прежнего Kafka-топика (`layout-legacy-kafka.json`) временный, уходит в 2.0 и под контракт не подпадает.

### Поля каждой записи

| Поле | Тип | Что содержит | Источник имени |
| --- | --- | --- | --- |
| `@timestamp` | string | Время записи, UTC | модель записи OTel (Timestamp) |
| `level` | string | Уровень | модель записи OTel (SeverityText) |
| `logger` | string | Имя логгера. HTTP-записи: `observability.http.in`, `observability.http.out` | модель записи OTel (InstrumentationScope) |
| `message` | string | Сообщение, замаскированное | модель записи OTel (Body) |
| `trace_id` | string | Идентификатор трассы W3C | модель записи OTel (TraceId) |
| `span_id` | string | Идентификатор спана | модель записи OTel (SpanId) |
| `thread.name` | string | Поток | semconv `thread` |
| `service.name` | string | `spring.application.name` | semconv `service` |
| `service.version` | string | Переменная `SERVICE_VERSION`, иначе `unknown` | semconv `service` |
| `deployment.environment.name` | string | Переменная `DEPLOYMENT_ENVIRONMENT`, иначе `unknown` | semconv `deployment` |
| `telemetry.distro.name` | string | `observability-starter` — какой библиотекой сделана запись | semconv `telemetry` |
| `telemetry.distro.version` | string | Версия библиотеки | semconv `telemetry` |
| `user.id` | string | Пользователь, из заголовка `UserGuid` | semconv `user` |
| `device.id` | string | Устройство, из заголовка `DeviceId` | semconv `device` |
| `uz.kapitalbank.correlation_id` | string | Сквозной идентификатор запроса (совместимость со старыми сервисами) | своё, в OTel нет |
| `uz.kapitalbank.client_device_id` | string | Заголовок `clientDeviceId`; если его нет — равен `correlation_id` | своё, в OTel нет |

### Поля записи с исключением

| Поле | Тип | Что содержит | Источник имени |
| --- | --- | --- | --- |
| `exception.type` | string | Полное имя класса исключения | semconv `exception` |
| `exception.message` | string | Сообщение исключения, замаскированное | semconv `exception` |
| `exception.stacktrace` | string | Стек, замаскированный и обрезанный | semconv `exception` |

### Поля HTTP-записей

Одна запись на обмен: `observability.http.in` — входящий запрос к сервису (HTTP server в терминах OpenTelemetry), `observability.http.out` — исходящий вызов через RestTemplate (HTTP client).

| Поле | Тип | Где | Что содержит | Источник имени |
| --- | --- | --- | --- | --- |
| `http.request.method` | string | in, out | Метод | semconv `http` |
| `url.scheme` | string | in | Схема запроса: `http` или `https` | semconv `url` |
| `url.path` | string | in | Путь без query, номер карты в пути замаскирован | semconv `url` |
| `http.route` | string | in | Шаблон маршрута Spring MVC, например `/api/v1/cards/{id}` | semconv `http` |
| `url.full` | string | out | Схема, хост, порт и путь, без query и учётных данных | semconv `url` |
| `server.address` | string | out | Хост вызываемого сервиса: по нему строятся ошибки и задержки по партнёрам | semconv `server` |
| `server.port` | number | out | Порт; если в URL его нет — по схеме (80, 443) | semconv `server` |
| `http.response.status_code` | number | in, out | Статус; при исключении — 500 (in) или нет поля (out) | semconv `http` |
| `error.type` | string | in, out | Класс исключения, если обмен упал; иначе код статуса строкой: у входящих для 5xx, у исходящих для 4xx и 5xx | semconv `error` |
| `http.request.header.<имя>` | array | in, out | Разрешённый заголовок, имя в нижнем регистре, значения массивом строк | semconv `http` |
| `http.request.body.size` | number | in, out | Полный размер тела запроса; у входящего с пустым телом поля нет, у исходящего — 0 | semconv `http` |
| `http.request.body.content` | string | in, out | Тело запроса, замаскированное и обрезанное до `max-body-bytes` | semconv `http` |
| `http.response.body.size` | number | in, out | Размер тела ответа | semconv `http` |
| `http.response.body.content` | string | in, out | Тело ответа, замаскированное и обрезанное | semconv `http` |
| `uz.kapitalbank.duration_ms` | number | in, out | Длительность обмена, мс | своё: в OTel длительность — метрика, атрибута для записи нет |
| `uz.kapitalbank.error.code` | string | in, out | Бизнес-код ошибки из тела ответа 4xx/5xx: верхнеуровневое `code`, `errorCode`, `error_code` или `error.code` | своё, в OTel нет |

Поля тел есть только у 4xx, 5xx и исключений, а у успешных обменов — при `LIB_OBSERVABILITY_HTTP_LOG_BODIES=ALWAYS` и пути из `LIB_OBSERVABILITY_HTTP_LOG_BODY_PATHS`. `url.template` (шаблон пути исходящего вызова) в OpenTelemetry есть, но на Spring 6.0 (Boot 3.1) RestTemplate не передаёт шаблон интерцептору, поэтому поле не пишется: стабильно заполнить его для всех сервисов нельзя.

Пример записи:

```json
{"@timestamp":"2026-11-25T12:00:00.123Z","level":"ERROR","logger":"observability.http.out","thread.name":"http-nio-8080-exec-1","message":"HTTP out POST https://humo.internal/api/pay -> 502 in 37 ms","service.name":"p2p-service","service.version":"1.4.2","deployment.environment.name":"prod","telemetry.distro.name":"observability-starter","telemetry.distro.version":"1.0.0-rc3","trace_id":"4bf92f3577b34da6a3ce929d0e0e4736","span_id":"00f067aa0ba902b7","user.id":"5d1c…","uz.kapitalbank.correlation_id":"c0ffee00-0000-4000-8000-000000000001","http.request.method":"POST","url.full":"https://humo.internal/api/pay","server.address":"humo.internal","server.port":443,"http.response.status_code":502,"error.type":"502","uz.kapitalbank.duration_ms":37,"http.request.header.content-type":["application/json"],"http.request.body.size":14,"http.request.body.content":"{\"amount\":100}","http.response.body.size":29,"http.response.body.content":"{\"error\":{\"code\":\"DECLINED\"}}","uz.kapitalbank.error.code":"DECLINED"}
```

## Kafka

Режим `STDOUT_AND_KAFKA` (по умолчанию в 1.x) включается, только если задан адрес Kafka: `LOGGING_KAFKA_SERVERS` или `logging.appender.kafka.bootstrap.servers`. Без адреса логи идут только в stdout, и при старте пишется предупреждение.

Настройки — те же свойства `logging.appender.kafka.*` и переменные `LOGGING_KAFKA_SERVERS`, `_TOPIC`, `_USERNAME`, `_PASSWORD`, `_PROTOCOL`, `_SASL_MECHANISM`, что у старой библиотеки, с теми же значениями по умолчанию, в том числе `sasl.mechanism=SASL_PLAINTEXT` и `ScramLoginModule`. Если протокол `SASL_*`, а механизм не из `PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512`, `GSSAPI`, `OAUTHBEARER`, при старте выводится предупреждение: в таком случае аппендер Kafka не работает.

Если Kafka недоступна, запросы не ждут. События копятся в очереди на 1024 штуки; при переполнении новые события в Kafka не уходят (в stdout они уже есть), и log4j пишет предупреждение. Старт с недоступной Kafka проверен, остановка занимает до 3 с.

Формат записи в топике — прежний набор полей. Отличия от старой библиотеки:
- стек ошибки — строка;
- значения MDC выводятся только для известных ключей;
- поля `http.*` вынесены в отдельные ключи.

Перед пилотом стоит сверить формат с настоящим сообщением из топика и проверить, что его потребители это принимают.

| Версия | Режим по умолчанию | Когда |
| --- | --- | --- |
| 1.x | `STDOUT_AND_KAFKA` | Сейчас, пока Vector не собирает stdout |
| 2.0 | `STDOUT`; Kafka и `kafka-clients` убраны | Когда Vector собирает stdout и записи в Loki совпадают с Kafka |

## Подключение

В `configurations.all` должен остаться `exclude group: 'org.springframework.boot', module: 'spring-boot-starter-logging'`, как сейчас. Если Logback всё же остался на classpath, библиотека не трогает конфигурацию логов и пишет ошибку при старте. Spring Boot при этом может не запуститься из-за двух реализаций SLF4J, поэтому исключение обязательно.

Конфигурация логов библиотеки главнее `logging.config` сервиса. Если сервис задавал свой `logging.config`, при старте выводится предупреждение: его аппендеры (файлы, другие топики) больше не используются. Вернуть свою конфигурацию можно только явно: `LIB_OBSERVABILITY_LOG_ENABLED=false`.

Если сервис исключал из старой библиотеки springdoc через блок `exclude` у её зависимости, этот блок уйдёт вместе с ней, и Swagger придёт из новой. Чтобы Swagger был не нужен, достаточно исключения `springdoc-openapi-starter-webmvc-ui` в `configurations.all`.

При старте библиотека пишет строку о своём состоянии, например `lib-observability: ВКЛЮЧЕНА; correlation=on; http-log=on; bodies=ON_ERROR; logging.config=classpath:lib-observability/log4j2-json-kafka.xml`.

Не поддерживаются: реактивные сервисы на WebFlux (gateway), `RestClient`, `WebClient`, Feign, batch-слушатели Kafka. Сейчас у сервисов `RestClient`, Feign и batch-слушателей нет.

## Переключатели

| Переменная | По умолчанию | Что делает |
| --- | --- | --- |
| `LIB_OBSERVABILITY_ENABLED` | `true` | Главный выключатель логов, корреляции и HTTP-логирования (actuator, метрики, трассировка и Swagger остаются) |
| `LIB_OBSERVABILITY_LOG_ENABLED` | `true` | `false` — конфигурация логов сервиса вместо библиотечной; маскирование строк лога в этом режиме не работает, поэтому только для аварийного отката |
| `LIB_OBSERVABILITY_LOG_OUTPUT` | `STDOUT_AND_KAFKA` | `STDOUT` — только stdout |
| `LIB_OBSERVABILITY_LOG_FORMAT` | `JSON` | `TEXT` — читаемый формат для локального запуска |
| `LIB_OBSERVABILITY_CORRELATION_ENABLED` | `true` | Корреляция в HTTP и Kafka |
| `LIB_OBSERVABILITY_CORRELATION_USER_CONTEXT_HOSTS` | пусто | Хосты, которым передаются `UserGuid` и `DeviceId` (`*.svc.cluster.local,limits`). Пусто — всем, как старая библиотека. До массового раската стоит задать список внутренних хостов в общем манифесте |
| `LIB_OBSERVABILITY_HTTP_LOG_ENABLED` | `true` | HTTP-логирование |
| `LIB_OBSERVABILITY_HTTP_LOG_BODIES` | `ON_ERROR` | Тела успешных обменов: `ON_ERROR` — нет, `ALWAYS` — да. Тела ошибок пишутся всегда |
| `LIB_OBSERVABILITY_HTTP_LOG_BODY_PATHS` | `/**` | Ограничить тела успешных обменов при `ALWAYS` частью путей |
| `LIB_OBSERVABILITY_HTTP_LOG_EXCLUDE_PATHS` | actuator, Swagger, OTP, SMS, Keycloak | Пути без логирования вовсе (список заменяет значение по умолчанию) |
| `LIB_OBSERVABILITY_HTTP_LOG_MAX_BODY_BYTES` | `4096` | Сколько байт тела в логе, от 256 до 65536 |
| `MANAGEMENT_ENDPOINT_IDS`, `ENABLE_SWAGGER`, `SWAGGER_BASE_URL` | как раньше | Те же переменные, что у старой библиотеки |

## Изменения версий

**1.0.0-rc3.** Формат логов приведён к OpenTelemetry semantic conventions до выпуска 1.0.0, с которого он фиксируется как контракт. Код сервисов и настройки менять не нужно, DevOps один раз меняют разбор в Vector:

| Было (rc1, rc2) | Стало |
| --- | --- |
| `thread` | `thread.name` |
| `deployment.environment` | `deployment.environment.name` |
| `user_guid`, `device_id` | `user.id`, `device.id` |
| `correlation_id`, `client_device_id` | `uz.kapitalbank.correlation_id`, `uz.kapitalbank.client_device_id` |
| `error.type`, `error.message`, `error.stack_trace` (записи с исключением) | `exception.type`, `exception.message`, `exception.stacktrace` |
| `http.request.headers` (строка `Имя=значение; …`) | `http.request.header.<имя>` (массив строк на заголовок) |
| `http.request.body.bytes`, `http.response.body.bytes` | `http.request.body.size`, `http.response.body.size` |
| `event.duration_ms` | `uz.kapitalbank.duration_ms` |
| `http.direction` | убрано: направление — в `logger` (`observability.http.in` / `.out`) |
| `data.*` | `uz.kapitalbank.data.*` |

Новые поля: `telemetry.distro.name` и `telemetry.distro.version` во всех записях; `url.scheme` у входящих; `server.address` и `server.port` у исходящих; `error.type` с кодом статуса у ошибочных ответов без исключения; `uz.kapitalbank.error.code` с бизнес-кодом из тела ответа.

**1.0.0-rc2** (по сравнению с rc1, которая была на тестировании). Код сервисов и настройки менять не нужно.

- Тела ошибок пишутся всегда, флаг `LIB_OBSERVABILITY_HTTP_LOG_BODIES` влияет только на успешные обмены; тела обрезаются с меткой `...[truncated]`, а не обрываются log4j на 8192 символах.
- Формат логов закреплён контракт-тестом. Для DevOps два изменения: у исключения без сообщения поля сообщения исключения больше нет (раньше был `null`), а одна строка лога теперь может быть длиннее 8 КБ: при `max-body-bytes` 4096 по умолчанию — порядка 10–20 КБ, при поднятом до 64 КБ — сотни КБ (два тела с экранированием JSON и кириллицей в UTF-8). Предел длины строки в Vector (`max_line_bytes`) и Loki должен это вмещать.
- Словарь маскирования расширен (поля процессинга, документы, суффиксы языка, номера на конце имени), см. `docs/masking-dictionary-changes-2026-11-25.md`; поля команды применяются не слабее заданного; словарь закреплён снимком.
- Буферы log4j ограничены пулом из 16 контекстов: удержание памяти около 5 МБ на сервис независимо от числа ядер и потоков.

## Сборка и проверка

`./gradlew build` запускает модульные тесты и отдельной задачей `e2eTest` — сквозные запуски Spring Boot, каждый в своей JVM:
- логи в режиме Kafka с недоступным брокером;
- actuator, Prometheus с тегом `application`, `traceId` и `userGuid` в логах;
- `LogFormatContractTest` и `LogFormatContractKafkaTest` — контракт формата логов в обеих конфигурациях вывода: имена и типы полей каждой записи сверяются с эталоном `log-format-contract.json`, проверяются время, уровни, отсутствие `null` и дубликатов;
- `TomcatTransparencyTest` — настоящий Tomcat: приложение получает тело байт в байт (JSON, 1 МБ, chunked, `getReader` с кодировкой, форма, multipart), а у каждой ошибки (400, 404, 405, 415, 500, асинхронный 503, тело отдельным пакетом) в логе есть замаскированное тело.

Защитные модульные тесты:
- `PiiLeakCorpusTest` — корпус ПДн в JSON, XML, тексте и форме, целиком и обрезанный на каждом смещении: тест падает, если в выводе есть контрольное значение или любой номер карты. Изменение маскера принимается только вместе с новыми записями в корпусе;
- `ServiceBeansUnchangedTest` — конвертеры MVC и `RestTemplate`, `ObjectMapper` и `WebMvcConfigurer` сервиса одинаковы с библиотекой и без неё.

`./gradlew publishToMavenLocal` — локальная публикация, `./gradlew publish` с `NEXUS_USER` и `NEXUS_PASSWORD` — в Nexus. Адрес репозитория снапшотов в `build.gradle` нужно сверить с Nexus банка.
