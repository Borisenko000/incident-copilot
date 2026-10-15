# Фаза 2a — ingestion: план

Каждая группа — небольшой шаг с diff, объяснением и коммитом (после подтверждения). Лекции проводятся до первого кода группы. TDD — там, где указано в `tech-stack.md`: доменные правила, идемпотентность, маскировка.

## 1. Тестовая база и зависимости

1.1. `pom.xml`: добавить `spring-boot-starter-validation`. Проверить, какой Jackson приходит со `starter-webmvc` (2 или 3), записать в `tech-stack.md`.
1.2. `AbstractIntegrationTest`: `@SpringBootTest(RANDOM_PORT)` + `@Import(TestcontainersConfiguration.class)`; перевести на него `HealthEndpointTest` и `SchemaMigrationTest` — один контекст и один контейнер на прогон.
1.3. `mvn test` зелёный, в логе один запуск контейнера.

## 2. Домен и маскировка (TDD)

Лекция: маскировка секретов и PII, prompt injection.

2.1. `domain`: `Severity`, `IncidentStatus`, `InvestigationStatus`, `IncidentEvent` (record, нормализованное событие).
2.2. Тесты `Fingerprint` → реализация: одинаковые события → один ключ; регистр и пробелы не влияют; разный сервис/тип → разный ключ; пустой `className`.
2.3. Тесты `EventSanitizer` → реализация: каждое правило из requirements.md, вложенные поля, `attributes` с секретным ключом, текст без секретов не меняется.

## 3. Use case приёма (TDD)

Лекция: транзакции и идемпотентность.

3.1. Порт `IncidentRepository`; in-memory fake для юнит-тестов.
3.2. Тесты `IngestEventService` → реализация: новое событие → инцидент + событие + расследование `QUEUED` + задание; повтор → `deduplicated`, без записей; событие в окне → привязка, `updated_at` и severity обновлены, без нового расследования; вне окна или инцидент `RESOLVED` → новый инцидент; маскировка вызывается до сохранения; lock берётся до проверок.
3.3. `ArchitectureTest`: убрать `allowEmptyShould(true)` — пакеты `domain`/`application` уже не пусты.

## 4. Адаптер PostgreSQL

4.0. `V3__incident_fingerprint_idx.sql`: индекс `ops.incidents (fingerprint, status, updated_at)`.
4.1. `JdbcIncidentRepository` на `JdbcClient`: advisory lock, поиск события и открытого инцидента в окне, INSERT'ы, `UPDATE updated_at/severity`, чтение карточки.
4.2. Интеграционные тесты репозитория: окно корреляции по `updated_at`, `payload` пишется как `jsonb`, карточка считает `eventCount` и берёт последнюю версию расследования.

## 5. `POST /api/v1/events`

5.1. `config`: `@Configuration` создаёт `IngestEventService`, `EventSanitizer`, `Clock`; `copilot.ingestion.correlation-window` в `application.yml`.
5.2. `TransactionalIngestion` (`@Transactional`) → use case; `EventController` с повтором после `DuplicateKeyException`; DTO с Bean Validation; обработчик ошибок → `ProblemDetail` (`400`/`413`/`422`); лимит тела 16 KB.
5.3. Интеграционные тесты через HTTP: `202` + `Location` + 4 строки в БД; повтор → тот же `incidentId`, строк не прибавилось; секрет из запроса в БД замаскирован; сбой при INSERT задания → откат всего; два одинаковых запроса параллельно → одно событие; два разных события с одним fingerprint параллельно → один инцидент; `400`, `413`, `422`.

## 6. `GET /api/v1/incidents/{id}`

6.1. `IncidentController` → чтение карточки через порт.
6.2. Тесты: `200` с полями из requirements.md после приёма события; `404`; `400` на не-UUID.

## 7. Завершение фазы

7.1. Пройти `validation.md`, включая ручную проверку curl.
7.2. Отметить пункты 2a в `roadmap.md`; заполнить «Отклонения» в `requirements.md` (пакет `config` и всё, что изменится по ходу).
7.3. Обновить README §5 (контракт API) и §7.4 (коррелированное событие не создаёт нового расследования).
7.4. Код-ревью ветки, показать итоговый diff, согласовать push и PR.
