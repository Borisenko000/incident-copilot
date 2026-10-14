# Фаза 1 — setup: требования

Ветка: `feature/phase-1-setup`. Пункты roadmap: каркас Spring Boot, `docker-compose.yml`, Flyway-миграции (схемы `ops` и `knowledge`, 7 таблиц), `/actuator/health` → `UP`.

## Цель

Получить приложение, которое стартует, подключается к локальной PostgreSQL 16 + pgvector, создаёт полную схему БД миграцией и сообщает о своём состоянии через health. На этом фундаменте фаза 2 сразу пишет приём событий.

## Объём

### Входит

1. **Каркас.** Spring Boot 4.1.1, Java 21, один Maven-модуль. Главный класс `dev.polina.copilot.IncidentCopilotApplication`; заглушка `dev.polina.Main` удаляется. `.gitignore` для `target/` и `.idea/`. Пакеты слоёв заранее не создаются — появятся вместе с первыми классами.
2. **Docker Compose.** `docker-compose.yml`: образ `pgvector/pgvector:pg16`, порт `5432`, именованный volume. Локальные учётные данные `copilot/copilot`; в `application.yml` — через `${DB_USER:copilot}`, `${DB_PASSWORD:copilot}`, `${DB_URL:...}`.
3. **Flyway `V1__init.sql`** — полная схема по README §7.2:
   - `CREATE EXTENSION IF NOT EXISTS vector`; схемы `ops` и `knowledge`;
   - таблицы `ops.incidents`, `ops.incident_events`, `ops.investigations`, `ops.evidence`, `ops.work_items`, `knowledge.sources`, `knowledge.chunks` со всеми колонками из §7.2;
   - CHECK статусов: incident `OPEN | RESOLVED | CLOSED`; investigation `QUEUED | RUNNING | DIAGNOSED | INCONCLUSIVE | FAILED`; work_item `READY | LEASED | DONE | FAILED`; `knowledge.sources.status` — минимум `APPROVED` и неутверждённые значения (`DRAFT`, `REJECTED`);
   - UNIQUE: `ops.incident_events(source, event_id)`, `ops.investigations(incident_id, version)`, `knowledge.chunks(source_id, chunk_no)`, частичный UNIQUE `ops.work_items(dedupe_key) WHERE dedupe_key IS NOT NULL`;
   - индексы: `ops.incidents(service, status, updated_at)`, `ops.evidence(incident_id, observed_at)`, `ops.work_items(status, available_at)`;
   - FK `ops.work_items.incident_id/investigation_id` — nullable (§7.1);
   - `knowledge.chunks.embedding vector(1024)`.
4. **Actuator.** Наружу открыт только endpoint `health`; в нём виден компонент `db`.
5. **ArchUnit-тест границ (NFR-01).** Классы из `..domain..` и `..application..` не зависят от `org.springframework..`, `org.springframework.ai..`, `java.sql..`, `javax.sql..`, `chat.giga..`. Пока пакеты пусты, правило проходит с `allowEmptyShould(true)`.

### Откладываем

| Что | Когда |
|---|---|
| Java-код домена, репозитории, REST | фаза 2 |
| `ops.feedback` | P1 |
| HNSW-индекс по `embedding` | после EXPLAIN/измерений (7b или позже) |
| Spring AI и GigaChat-стартер в `pom.xml` | фаза 6 |
| CI | P1 |
| `spring-boot-docker-compose` (автозапуск БД) | не делаем: БД поднимается `docker compose up` |

## Ключевые решения

| Решение | Почему |
|---|---|
| **Spring Boot 4.1.1** вместо 3.5.x | OSS-поддержка 3.5 закончилась в июне 2026. Spring AI 2.0.x (GA 2026-06-12) официально рассчитана на Boot 4.0/4.1; GigaChat-стартер `chat.giga:spring-ai-starter-model-gigachat:2.0.0` зависит от `spring-boot-starter-restclient`, который есть только в Boot 4. Сборка проверяется в группе 1 плана |
| Стартеры Boot 4: `spring-boot-starter-webmvc`, `spring-boot-starter-jdbc`, `spring-boot-starter-flyway`, `spring-boot-starter-actuator` + `flyway-database-postgresql`, `postgresql` | В Boot 4 стартер web переименован, а Flyway без отдельного стартера не автоконфигурируется |
| Тесты: `spring-boot-starter-test`, `spring-boot-testcontainers`, Testcontainers 2.x (`testcontainers-postgresql`, `testcontainers-junit-jupiter`), ArchUnit | Testcontainers 2.x — актуальная линия для Boot 4; модули переименованы |
| Вся схема одной миграцией V1 | Схема видна целиком; следующие фазы сразу пишут код. Изменения — только новыми миграциями, V1 после коммита не редактируется |
| `vector(1024)` уже сейчас | pgvector проверяется в фазе 1; таблица до 7a пустая, поэтому смена размерности — дешёвая миграция |
| Перечисления — `text` + CHECK, не PostgreSQL `ENUM` | CHECK проще менять миграцией; Java-enum появится в домене |
| Первичные ключи — `uuid`, генерирует приложение (в V1 без `DEFAULT`) | Идентификатор известен до INSERT; домен не зависит от БД. *Уточнить в фазе 2; если понадобится `DEFAULT gen_random_uuid()` — новая миграция* |
| Время — `timestamptz` | Нет путаницы часовых поясов |

## Граничные случаи

- Docker не запущен → интеграционные тесты падают с ошибкой Testcontainers; это ожидаемое поведение, требование описано в README/CLAUDE.md.
- Повторный старт приложения → Flyway видит применённую V1 и ничего не меняет.
- Порт 5432 занят локальной PostgreSQL → `docker compose up` падает; решение — остановить локальную БД или сменить порт: `DB_PORT=5433` для `docker compose` и `DB_URL=jdbc:postgresql://localhost:5433/copilot` для приложения.
- Изменение уже применённой V1 → Flyway падает на checksum. Правило: только новые миграции.

## Допущения

- Размерность embedding GigaChat `Embeddings` = 1024 (по документации). Проверка — спайк фазы 6.
- Версии зафиксированы по Maven Central на 2026-10-14: Spring Boot 4.1.1, Spring AI 2.0.1, GigaChat-стартер 2.0.0. Версии ArchUnit и Testcontainers берутся из BOM Boot или последние стабильные на момент реализации и записываются в `tech-stack.md`.

## Лекции фазы (по `specs/learning-plan.md`)

До первого кода соответствующей группы плана:
- модульный монолит и hexagonal (ports/adapters);
- Docker Compose, PostgreSQL, Flyway;
- pgvector и vector DB;
- работа с AI co-pilot: план до кода, `CLAUDE.md`, specs, ревью diff.

## Отклонения

1. **Spring Boot 4.1.1 собрался**, откат на 3.5.x не понадобился. Версии из BOM Boot: Testcontainers 2.0.5, Flyway 12.4.0, драйвер PostgreSQL 42.7.13; ArchUnit 1.5.1 — явно в `pom.xml`.
2. **V1 сверх перечисленного:** `DEFAULT now()` у `created_at`/`updated_at`/`received_at` (у `id` DEFAULT по-прежнему нет); `CHECK (attempts >= 0)` и `CHECK (version > 0)`; явные имена UNIQUE-ограничений; `knowledge.chunks` → `sources` с `ON DELETE CASCADE`.
3. **`knowledge.chunks.embedding` nullable** — по README §8.2 без embeddings поиск работает только по FTS.
4. **Не добавлено** (появится новыми миграциями под реальные запросы): CHECK для `kind`/`severity`, UNIQUE на `knowledge.sources.origin_uri` (фаза 7a), индекс по `fingerprint` для корреляции (фаза 2).
5. **Тесты:** отдельного контекстного теста нет — его роль выполняет `HealthEndpointTest`; ручная проверка «`/actuator/env` → 404» дополнительно автоматизирована. HTTP в тесте — `RestClient` + `RANDOM_PORT`: в Boot 4 `TestRestTemplate` вынесен в отдельный модуль `spring-boot-resttestclient`.
6. **Flyway** хранит `flyway_schema_history` в схеме `public` (по умолчанию).
7. **Стиль коммитов:** три коммита фазы сначала были оформлены как `build(setup)` (×2) и `feat(db)` вопреки `tech-stack.md`; до push сообщения переписаны в `chore(setup)` и `feat(setup)`, содержимое не менялось.
8. **Code review (`/code-review high`) → миграция `V2__integrity_constraints.sql`** (V1 не редактируем): CHECK «`LEASED` ⇒ заполнены `leased_until` и `lease_owner`»; составной FK `(investigation_id, incident_id)` у `evidence` и `work_items` — нельзя сослаться на расследование чужого инцидента; индексы `incident_events(incident_id)`, `evidence(investigation_id)`. `docker-compose.yml`: порт только на `127.0.0.1` и настраивается через `DB_PORT`.
9. **Из code review отложено:** UNIQUE-идентичность `knowledge.sources` (7a, см. п. 4); запрет Telegram в ArchUnit-правиле (P1, когда будет выбрана библиотека); обновление `updated_at`, `allowEmptyShould` и общий базовый класс интеграционных тестов — в roadmap фазы 2.
