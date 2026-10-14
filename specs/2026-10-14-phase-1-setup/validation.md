# Фаза 1 — setup: валидация

Ветку `feature/phase-1-setup` можно сливать, когда выполнены все пункты.

## Автотесты (`mvn test`, Docker запущен)

| Критерий roadmap | Тест | Что проверяет |
|---|---|---|
| Каркас стартует | контекстный тест Spring Boot | контекст поднимается на Testcontainers |
| Flyway: `ops` и `knowledge`, 7 таблиц | интеграционный тест схемы | V1 применена; в `ops` ровно `incidents`, `incident_events`, `investigations`, `evidence`, `work_items`; в `knowledge` ровно `sources`, `chunks`; расширение `vector` установлено |
| Ограничения схемы | интеграционный тест схемы | повторный `(source, event_id)` → ошибка UNIQUE; недопустимый статус → ошибка CHECK; `vector(1024)` вставляется, вектор другой длины — отклоняется |
| `/actuator/health` → `UP` | тест health | `200`, `status=UP`, компонент `db` = `UP` |
| Границы слоёв (NFR-01) | ArchUnit-тест | `domain`/`application` не зависят от Spring, Spring AI, JDBC, GigaChat |

Тесты не обращаются к сети, кроме Docker-образа Testcontainers.

## Ручная проверка

1. `docker compose up -d` → контейнер healthy.
2. Запуск приложения → в логе Flyway применяет `V1`.
3. `curl localhost:8080/actuator/health` → `{"status":"UP",...}`.
4. Остальные actuator endpoints недоступны (например, `/actuator/env` → `404`).
5. Повторный запуск приложения → Flyway сообщает, что схема актуальна, миграции не применяются заново.

## Процесс

- [ ] Лекции фазы проведены (см. requirements.md).
- [ ] Каждый коммит: diff просмотрен и объяснён, сообщение в стиле Conventional Commits.
- [ ] Пункты фазы 1 отмечены в `specs/roadmap.md`.
- [ ] Раздел «Отклонения» в `requirements.md` заполнен (или явно «нет»).
- [ ] Версии зависимостей записаны в `specs/tech-stack.md`.
- [ ] В коде и конфигурации нет секретов, кроме локальных значений по умолчанию `copilot/copilot`.
