# Изменения словаря маскирования (ревью 2026-11-25)

Список для подтверждения командами. Поля найдены в коде сервисов банка (4029 имён полей из 74 сервисов) и сверены до и после правки: правило поменялось только у полей ниже. Если поле в вашем сервисе означает другое и маскирование мешает разбору, напишите, — поле можно открыть точечно, не ослабляя базовые правила.

| Поле | Было | Стало | Сервисы |
| --- | --- | --- | --- |
| `accessKey` | открыто | скрыто целиком | cdn |
| `address1` | открыто | скрыто целиком | card-service |
| `address2` | открыто | скрыто целиком | card-service |
| `authCred` | открыто | скрыто целиком | uzcard-adapter |
| `birthPlace` | открыто | скрыто целиком | digital-onboarding, digital-onboarding-scheduler, identification-service |
| `docGivePlace` | открыто | скрыто целиком | digital-onboarding |
| `docGivePlaceId` | открыто | скрыто целиком | digital-onboarding |
| `docIssueDate` | открыто | скрыто целиком | digital-onboarding, digital-onboarding-scheduler, identification-service |
| `docIssuer` | открыто | скрыто целиком | digital-onboarding, digital-onboarding-scheduler, identification-service |
| `docIssuerName` | открыто | скрыто целиком | digital-onboarding |
| `documentGivePlace` | открыто | скрыто целиком | digital-onboarding |
| `documentSeria` | открыто | скрыто целиком | digital-onboarding, loan-claim, loan-service |
| `documentSerialNumber` | открыто | скрыто целиком | deposits |
| `expirationMonth` | открыто | скрыто целиком | card-service, encryption-service |
| `expirationYear` | открыто | скрыто целиком | card-service, encryption-service |
| `identityDocumentIssueDate` | открыто | скрыто целиком | digital-onboarding |
| `identityDocumentIssuer` | открыто | скрыто целиком | digital-onboarding |
| `salt` | открыто | скрыто целиком | accounts, anti-fraud-service, auth-scheduler, auth-service, card-order-service, card-service, deposits, encryption-service, recent-participants, tbm-scheduler |
| `taxPayerCode` | открыто | скрыто целиком | digital-onboarding |
| `clientSecondName` | открыто | первая буква | digital-onboarding |
| `firstNameEn` | открыто | первая буква | identification-service |
| `firstNameLat` | открыто | первая буква | digital-onboarding |
| `firstNameLatin` | открыто | первая буква | digital-onboarding, p2p-service |
| `lastNameEn` | открыто | первая буква | identification-service |
| `lastNameLat` | открыто | первая буква | digital-onboarding |
| `lastNameLatin` | открыто | первая буква | digital-onboarding, p2p-service |
| `patronym` | открыто | первая буква | loan-claim, loan-service |
| `patronymCyr` | открыто | первая буква | digital-onboarding |
| `patronymicLatin` | открыто | первая буква | digital-onboarding |
| `patronymLatin` | открыто | первая буква | digital-onboarding |
| `recipientNameLocal` | открыто | первая буква | card-service |
| `secondNameLat` | открыто | первая буква | digital-onboarding |
| `surnameCyr` | открыто | первая буква | digital-onboarding |
| `surnameLatin` | открыто | первая буква | digital-onboarding |
| `phones` | открыто | последние 4 | digital-onboarding |
| `telefon` | открыто | последние 4 | paynet-adapter |
| `authorizationDateTime` | скрыто целиком | открыто | card-service, tieto-adapter |
| `authorizationResponse` | скрыто целиком | открыто | esb-auth-scheduler |

Кроме того, теперь скрываются под любым именем коды безопасности карты (`securityCode`, `secureCode`, `csc`, `cvn`, в том числе из 3 цифр), отчество процессинга `M_NAME` и почта `E_MAILS`. Номер на конце любого слова и суффиксы языка (`En`, `Lat`, `Latin`, `Cyr`, `Local` и др.) больше не обходят правила.

Открытый вопрос: `cardName` в card-service — название карты, которое задаёт пользователь (по умолчанию строится из номера карты). Сейчас маскируется как имя (первая буква). Если название нужно при разборе обращений, его можно открыть только для card-service.
