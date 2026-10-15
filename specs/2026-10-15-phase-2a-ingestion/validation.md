# Фаза 2a — ingestion: валидация

Ветку `feature/phase-2a-ingestion` можно сливать, когда выполнены все пункты.

## Автотесты (`mvn test`, Docker запущен)

| Критерий roadmap | Тест | Что проверяет |
|---|---|---|
| Атомарный приём → `202` | интеграционный HTTP-тест | `202`, `Location`, тело `{incidentId, eventId, investigationStatus=QUEUED, deduplicated=false}`; в БД ровно 1 событие, 1 инцидент `OPEN`, 1 расследование `QUEUED` v1, 1 задание `READY` |
| Атомарность | интеграционный тест со сбоем | исключение при INSERT задания → в БД нет ни события, ни инцидента, ни расследования |
| Маскировка до сохранения | юнит-тесты `EventSanitizer` + интеграционный тест | каждое правило маскировки; секрет из запроса в `incident_events.payload` хранится как `***` |
| Повтор `(source, eventId)` | юнит-тест use case + HTTP-тест | `deduplicated=true`, тот же `incidentId`, количество строк во всех 4 таблицах не изменилось |
| Гонка дублей | интеграционный тест, 2 потока | одинаковые запросы параллельно → одно событие, оба ответа `202` с одним `incidentId` |
| Корреляция | юнит-тест use case + интеграционный тест | событие с тем же fingerprint в окне → тот же инцидент, `updated_at` обновлён, нового расследования нет; вне окна или инцидент `RESOLVED` → новый инцидент |
| Гонка корреляции | интеграционный тест, 2 потока | разные события с одним fingerprint параллельно → один инцидент |
| Fingerprint | юнит-тесты | детерминирован, не зависит от регистра и пробелов |
| Валидация | HTTP-тесты | невалидный JSON → `400`; тело > 16 KB → `413`; нет обязательного поля / неизвестная severity → `422`; во всех случаях `ProblemDetail` и ничего не сохранено |
| `GET /incidents/{id}` | HTTP-тесты | `200` с полями карточки и `latestInvestigation`; `404`; `400` на не-UUID |
| Границы слоёв (NFR-01) | `ArchitectureTest` | правило без `allowEmptyShould`; `domain`/`application` не зависят от Spring и JDBC |
| Один контейнер на прогон | все интеграционные тесты | наследуют `AbstractIntegrationTest` |

Тесты не обращаются к сети, кроме Docker-образа Testcontainers.

## Ручная проверка

1. `docker compose up -d`, запуск приложения.
2. `curl -i -X POST localhost:8080/api/v1/events -H 'Content-Type: application/json' -d @event.json` (пример из README §5.1 с `password=secret` в `error.message`) → `202`, `Location`.
3. Тот же запрос ещё раз → `202`, тот же `incidentId`, `"deduplicated": true`.
4. Тот же запрос с другим `eventId` → тот же `incidentId`, `"deduplicated": false`.
5. `curl localhost:8080/api/v1/incidents/<id>` → карточка, `eventCount: 2`, расследование `QUEUED`.
6. В БД: `SELECT payload FROM ops.incident_events` — вместо `secret` стоит `***`; в `ops.work_items` одно задание `READY`.

## Процесс

- [ ] Лекции фазы проведены (см. requirements.md).
- [ ] Каждый коммит: diff просмотрен и объяснён, сообщение в стиле Conventional Commits.
- [ ] Пункты фазы 2a отмечены в `specs/roadmap.md`.
- [ ] Раздел «Отклонения» в `requirements.md` заполнен (или явно «нет»).
- [ ] Новые зависимости и версия Jackson записаны в `specs/tech-stack.md`.
- [ ] README §5 соответствует реализованному контракту.
- [ ] Код-ревью ветки проведено, найденное исправлено или отложено с записью.
