# Tech stack

## Стек

| Технология | Почему |
|---|---|
| Java 21, Maven | Основной язык курса. Один модуль — меньше сложности сборки |
| Spring Boot 3.5.x | REST, транзакции и `@Scheduled` worker из коробки |
| PostgreSQL 16 + pgvector | Одна БД для данных, очереди задач (`SKIP LOCKED`) и векторов RAG — без отдельных брокеров и vector DB |
| Flyway | Версионируемые миграции схемы |
| JdbcTemplate / Spring Data JDBC | Нужен явный SQL (`SKIP LOCKED`, pgvector, FTS); JPA здесь мешает |
| GigaChat (chat + embeddings) | LLM доступна без VPN; размерность embeddings — 1024 по документации, подтвердить на спайке |
| Spring AI 1.1.x или `RestClient` | Клиент к GigaChat; выбор — по итогам спайка (фаза 6), в обоих случаях — за портом `LlmGateway` |
| Docker Compose | Локальный запуск БД одной командой |

## Модули

Один Maven-модуль, слои — пакеты `dev.polina.copilot.*`:
`domain` → `application` → `port` ← `adapter.in` / `adapter.out`, плюс `simulator`.
Пакеты `domain` и `application` не импортируют Spring AI, JDBC и клиент GigaChat.

## Интеграции

- GigaChat: chat, function calling, embeddings.
- Логи, метрики, трассировки и активность БД — только fixtures симулятора.

## Технические ограничения

- Аутентификации нет, запуск только локальный.
- TLS к GigaChat не отключать: сертификат НУЦ Минцифры добавляется в truststore.
- В LLM уходят только замаскированные данные (пароли, токены, строки подключения).
- Tools только читают данные; LLM не может выполнять SQL, shell или ходить по URL.

## Стратегия тестов

- **TDD (тест до кода):** доменные правила и переходы статусов, идемпотентность приёма, gates валидатора, маскировка секретов, фильтр версии в retrieval.
- **Тесты после кода:** адаптеры — REST-контроллеры, клиент GigaChat.
- **Инструменты:** JUnit 5, Mockito, Testcontainers (`pgvector/pgvector:pg16`), `ScriptedLlmGateway` — fake LLM с заранее заданными ответами.
- **Что считается покрытым:** у каждого критерия приёмки фазы есть автотест. Тесты не обращаются к реальному GigaChat.

## Определение готовности

Пункт roadmap готов, когда:
- `mvn test` зелёный;
- критерий приёмки проверен;
- diff просмотрен и объяснён;
- README и `specs/` обновлены, если изменился контракт.

## Стиль коммитов

Conventional Commits: `feat|fix|test|docs|refactor|chore(<фаза>): описание по-русски`, например `feat(ingestion): приём события с идемпотентностью`. Один логический шаг — один коммит.
