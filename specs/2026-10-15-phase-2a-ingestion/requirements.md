# Фаза 2a — ingestion: требования

Ветка: `feature/phase-2a-ingestion`. Пункты roadmap: `POST /api/v1/events` с атомарным сохранением и маскировкой, идемпотентность и корреляция, `GET /api/v1/incidents/{id}`, долги ревью фазы 1. Требования README: FR-01 (часть для REST), FR-02, FR-04, FR-17 (часть для БД), NFR-01, NFR-02.

## Цель

Принять событие об ошибке по REST, замаскировать в нём секреты и **атомарно** сохранить событие, инцидент, расследование (`QUEUED`) и задание в очередь. Повтор того же события не создаёт дублей, а похожие события склеиваются в один инцидент. После фазы 2a в `ops.work_items` лежат задания, которые фаза 3 начнёт забирать worker'ом.

## Объём

### Входит

1. **`POST /api/v1/events`** — приём события в формате README §5.1 → `202 Accepted` + `Location` + тело `{incidentId, eventId, investigationStatus, deduplicated}`.
2. **Маскировка секретов и PII** (`EventSanitizer`) до сохранения: во всех строковых полях, включая вложенные `error`, `correlation`, `attributes`, `technology`.
3. **Идемпотентность** по `(source, eventId)`: повтор → `202`, тот же `incidentId`, `deduplicated: true`, ни одной новой строки в БД.
4. **Корреляция:** новое событие с тем же `fingerprint` в окне корреляции привязывается к открытому инциденту.
5. **`GET /api/v1/incidents/{id}`** — карточка инцидента и статус последнего расследования.
6. **Долги ревью фазы 1:** явное обновление `updated_at` при каждом новом событии инцидента; убрать `allowEmptyShould(true)` из `ArchitectureTest`; общий базовый класс интеграционных тестов (один контекст и один контейнер на прогон).

### Откладываем

| Что | Когда |
|---|---|
| `POST /api/v1/incidents` (ручной ввод) | фаза 2b |
| Worker, lease, ретраи | фаза 3 |
| Новое расследование для коррелированного события (`ADDITIONAL`) | P1 |
| Re-open закрытого инцидента | P1; в 2a — новый инцидент |
| `correlationId` в `ProblemDetail`, лимит запросов (`429`) | P1 |
| Маскировка перед отправкой в LLM | фаза 6 (тот же `EventSanitizer`) |
| Нагрузочный тест NFR-04 (p95 ≤ 500 мс) | P1 |
| Webhook-адаптеры (Kafka DLT, Alertmanager) | P1 |

## Ключевые решения

### Данные

| Решение | Почему |
|---|---|
| **Fingerprint** = SHA-256 от нормализованной строки `type \| error.className \| service \| environment` (lower-case, trim; пустой `className` → `-`) | Детерминированно и без LLM (README §7.3). Одинаковая ошибка в одном сервисе даёт одинаковый ключ |
| `incidents.category` = `type` события, `severity` — из события | Первое событие задаёт инцидент |
| **Окно корреляции** — `copilot.ingestion.correlation-window`, по умолчанию `PT30M`. Ищется инцидент со статусом `OPEN`, тем же `fingerprint` и `updated_at` не старше окна | Скользящее окно: пока ошибка повторяется, инцидент остаётся тем же |
| Время для окна берётся **по часам сервера** (`Clock`), не из `occurredAt` | Часы клиента могут врать; `Clock` подменяется в тестах |
| Коррелированное событие: INSERT события + `UPDATE incidents SET updated_at = now`, severity повышается до максимума; **новые investigation и work_item не создаются** | Шторм из 100 одинаковых ошибок не должен порождать 100 вызовов LLM (решение согласовано) |
| `RESOLVED`/`CLOSED` инцидент с тем же fingerprint → новый инцидент | Не смешиваем независимые эпизоды (README §7.3) |
| `incident_events.payload` — **замаскированный** исходный JSON целиком | Аудит и будущий контекст для LLM; секреты в БД не попадают |
| Новое расследование: `version = 1`, `status = QUEUED`. Задание: `kind = INVESTIGATE`, `status = READY`, `dedupe_key = investigate:<investigationId>` | Формат, который ждёт фаза 3 |
| UUID генерирует приложение | Решение фазы 1 подтверждается: id нужен до INSERT для `Location` и связей |
| Миграция `V3__incident_fingerprint_idx.sql`: индекс `ops.incidents (fingerprint, status, updated_at)` | Под запрос корреляции; отложен в фазе 1 («Отклонения» п. 4). V1/V2 не редактируются |

### Транзакции и гонки

| Решение | Почему |
|---|---|
| Use case `IngestEventService` в `application` — чистая Java. Транзакцию открывает Spring-бин в `adapter.in` с `@Transactional`, который вызывает use case | `application` не импортирует Spring (ArchUnit). Вариант с `@Transactional` в адаптере согласован |
| Порядок в транзакции: `pg_advisory_xact_lock(hash(fingerprint))` → проверка `(source, eventId)` → поиск открытого инцидента → INSERT'ы → COMMIT → `202` | Advisory lock сериализует события с одним fingerprint; без него `SELECT then INSERT` создаёт два инцидента (README §7.3). Lock снимается сам при COMMIT/ROLLBACK |
| **Гонка дублей одного события** (два одинаковых запроса одновременно): второй INSERT падает на `UNIQUE(source, event_id)` → транзакция откатывается → **один повтор в новой транзакции** находит событие и отвечает `deduplicated: true` | Идемпотентность гарантирует сама БД, а не проверка в коде |
| Повтор выполняется **вне** транзакционного бина (в контроллере) | Вызов `@Transactional`-метода из того же класса — self-invocation: новая транзакция не откроется |
| Бины `application` создаются в `@Configuration` в пакете `dev.polina.copilot.config` | `@Service` в `application` запрещён ArchUnit. Пакета `config` нет в README §14 — записать в «Отклонения» |

### Порты

- `IncidentRepository` (в `port`, реализация `JdbcIncidentRepository` в `adapter.out` на `JdbcClient`): lock по fingerprint, поиск события, поиск открытого инцидента в окне, INSERT инцидента/события/расследования/задания, обновление инцидента, чтение карточки.
- Других портов в 2a нет: `Clock` — стандартный `java.time.Clock`, маскировка — чистая функция без внешних границ.

### API

**`POST /api/v1/events`** — тело по README §5.1.

| Поле | Обязательно | Ограничения |
|---|---|---|
| `eventId`, `source`, `service`, `environment`, `type` | да | непустые строки, ≤ 200 символов |
| `occurredAt` | да | ISO-8601 instant |
| `severity` | да | `LOW` \| `MEDIUM` \| `HIGH` \| `CRITICAL` |
| `summary`, `error{className, message, stackTrace}`, `correlation`, `attributes`, `technology` | нет | `attributes`/`technology`/`correlation` — объекты |

Ответы: `202` (новое, коррелированное или повтор) с `Location: /api/v1/incidents/{id}`; `400` — невалидный JSON; `413` — тело > 16 KB; `422` — нарушены правила полей. Ошибки — `ProblemDetail` (`type, title, status, detail`).

**`GET /api/v1/incidents/{id}`** → `200`:
```json
{"id": "...", "service": "...", "environment": "...", "category": "...", "severity": "HIGH",
 "status": "OPEN", "createdAt": "...", "updatedAt": "...", "eventCount": 3,
 "latestInvestigation": {"id": "...", "version": 1, "status": "QUEUED"}}
```
`404` — нет инцидента; `400` — id не UUID.

### Маскировка (TDD)

Значение заменяется на `***`, ключ остаётся видимым:
- `password`, `passwd`, `pwd`, `secret`, `token`, `api_key`/`apikey`, `access_key` в формах `key=value`, `key: value`, `"key":"value"`;
- заголовки `Authorization: Bearer …` / `Basic …`;
- учётные данные в URL и строках подключения: `scheme://user:pass@host` → `scheme://user:***@host`, `jdbc:…?password=…`;
- e-mail → `***@***`.

Маскируются и значения в `attributes`, у которых имя ключа совпадает со списком выше (например, `"password": "x"`).

### Структура пакетов

`domain` — `IncidentEvent`, `Severity`, `IncidentStatus`, `InvestigationStatus`, `Fingerprint`; `application` — `IngestEventService`, `EventSanitizer`, `IngestResult`; `port` — `IncidentRepository`; `adapter.in` — `EventController`, `IncidentController`, `TransactionalIngestion`, DTO и обработчик ошибок; `adapter.out` — `JdbcIncidentRepository`; `config` — сборка бинов.

### Зависимости

- `spring-boot-starter-validation` — Bean Validation для DTO в `adapter.in`.
- JSON — Jackson из `starter-webmvc`. В Boot 4 по умолчанию Jackson 3 (пакет `tools.jackson`); проверить в группе 1 плана.

## Граничные случаи

- Повтор того же `(source, eventId)` — `202`, `deduplicated: true`, новых строк нет, даже если тело повтора отличается (первое событие главнее).
- Два одинаковых запроса одновременно → одна запись; оба ответа с одним `incidentId`.
- Два **разных** события с одним fingerprint одновременно → один инцидент (advisory lock).
- Событие ровно на границе окна: `updated_at` старше окна → новый инцидент.
- Сбой любого INSERT'а → откат всего: ни события, ни инцидента, ни задания.
- Тело > 16 KB → `413`, ничего не сохраняется.
- Секрет в `stackTrace` или в `attributes` → в БД только `***`.
- `GET` несуществующего инцидента → `404` `ProblemDetail`.

## Допущения

- Одна установка, один источник истины для времени — часы сервера.
- Лимит 16 KB — из NFR-04; если реальные stack trace не помещаются, лимит выносится в настройку.
- Регулярные выражения маскировки ловят типовые форматы, а не все возможные; список расширяется тестами по мере находок.

## Лекции фазы (по `specs/learning-plan.md`)

До первого кода соответствующей группы плана:
- транзакции и идемпотентность приёма событий: ACID, `@Transactional` и прокси, self-invocation, advisory lock, `UNIQUE` как гарантия;
- маскировка секретов и PII до сохранения и до LLM; prompt injection: логи — это данные, а не инструкции.

## Отклонения

(заполняется в конце фазы)
