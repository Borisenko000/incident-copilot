# Фаза 2b — manual: валидация

Ветку `feature/phase-2b-manual` можно сливать, когда выполнены все пункты.

## Автотесты (`mvn test`, Docker запущен)

| Критерий | Тест | Что проверяет |
|---|---|---|
| Ручной ввод → `202` | HTTP-тест | `202`, `Location`, тело `{incidentId, eventId (UUID), investigationStatus=QUEUED, deduplicated=false}`; в БД 1 инцидент `OPEN`, 1 событие `source=MANUAL`, 1 расследование `QUEUED` v1, 1 задание `READY` |
| Тот же use case (FR-01) | юнит-тест use case | `openManualIncident` создаёт те же 4 записи, что и `ingest` для нового инцидента |
| Без корреляции (в обе стороны) | юнит-тесты + HTTP-тесты | открыт инцидент с тем же fingerprint → новый инцидент, у старого не изменились `updated_at` и число событий; авто-событие после ручного ввода идёт в авто-инцидент, а не в ручной |
| `eventId` от сервера | HTTP-тест | две одинаковые формы → два инцидента с разными `eventId` |
| Маппинг и значения по умолчанию | юнит-тесты `toDomain` | `type=MANUAL`, `severity=MEDIUM`; `title`→`summary`; `errorText`→`stackTrace`, первая непустая строка → `message`; `description`→`attributes.description` |
| Метрики не выдумываются | юнит-тест `toDomain` | в событии нет полей, кроме введённых и серверных (`correlation` пуст, `className` = null) |
| Маскировка | юнит-тест + HTTP-тест | секрет из `title`, `description`, `errorText`, а также `type`/`service`/`environment` формы хранится как `***` |
| Валидация | HTTP-тесты | нет `errorText` / `errorText` из пробелов / неизвестная severity → `422`; невалидный JSON → `400`; тело > 16 KB → `413`; ничего не сохранено |
| Регрессия 2a | все тесты 2a | зелёные без изменений |

## Ручная проверка

1. `docker compose up -d`, запуск приложения.
2. `curl -i -X POST localhost:8080/api/v1/incidents -H 'Content-Type: application/json' -d @manual.json` (пример README §5.2 с `password=secret` в `errorText`) → `202`, `Location`.
3. `curl localhost:8080/api/v1/incidents/<id>` → `category: MANUAL`, `severity: MEDIUM`, расследование `QUEUED`.
4. В БД: `payload` события содержит `***` вместо `secret`, `source = MANUAL`.

## Процесс

- [x] Каждый коммит: diff просмотрен и объяснён, сообщение в стиле Conventional Commits.
- [x] Пункт фазы 2b отмечен в `specs/roadmap.md`.
- [x] Раздел «Отклонения» в `requirements.md` заполнен (или явно «нет»).
- [x] README §5.2 соответствует реализованному контракту.
- [x] Код-ревью ветки проведено (`/code-review high`), найденное исправлено или отложено с записью в «Отклонениях» п. 3.
