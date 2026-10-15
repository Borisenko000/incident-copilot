# Фаза 2b — manual: план

Каждая группа — небольшой шаг с diff, объяснением и коммитом (после подтверждения). Лекций в фазе нет.

## 1. Use case ручного ввода (TDD)

1.1. Тесты `IngestEventServiceTest` → реализация `openManualIncident`: создаёт инцидент OPEN + событие + расследование `QUEUED` v1 + задание `READY`; при открытом инциденте с тем же fingerprint создаёт **новый** инцидент и не трогает старый; событие маскируется до сохранения.
1.2. `ingest` переиспользует тот же приватный путь «новый инцидент»; все тесты 2a остаются зелёными.

## 2. `POST /api/v1/incidents`

2.1. `ManualIncidentRequest` (Bean Validation) и `toDomain(Clock)`: серверный UUID, `source = MANUAL`, значения по умолчанию `type`/`severity`, маппинг полей из requirements.md.
2.2. `TransactionalIngestion.openManualIncident` (`@Transactional`); `POST` в `IncidentController` → `202` + `Location`.
2.3. Юнит-тесты маппинга `toDomain`: первая непустая строка → `error.message`, значения по умолчанию, `description` → `attributes`.
2.4. Интеграционные HTTP-тесты: `202` + 4 строки в БД, `source = MANUAL`; открытый авто-инцидент с тем же fingerprint не переиспользуется; повторная отправка → два инцидента; секреты в `payload` замаскированы; `422` (нет `errorText`, пустой `errorText`, неизвестная severity), `400`, `413`.

## 3. Завершение фазы

3.1. Пройти `validation.md`, включая ручную проверку curl.
3.2. Отметить пункт 2b в `roadmap.md`; заполнить «Отклонения».
3.3. Обновить README §5.2 (обязательные поля, ответ, значения по умолчанию).
3.4. Код-ревью ветки, итоговый diff, согласовать push и PR.
