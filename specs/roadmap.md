# Roadmap

Одна фаза = одна ветка `feature/phase-<N>-<name>` и один PR. Спека фазы — `specs/YYYY-MM-DD-phase-<N>-<name>/{requirements,plan,validation}.md`. Сквозной сценарий v1 — `DB_POOL_EXHAUSTED`.

## Фаза 1 — setup
- [x] Каркас Spring Boot 4.1, приложение стартует
- [x] `docker-compose.yml` с PostgreSQL 16 + pgvector
- [x] Flyway-миграции: схемы `ops` и `knowledge`, 7 таблиц
- [x] `/actuator/health` отвечает `UP`

## Фаза 2 — ingestion
- [ ] `POST /api/v1/events`: event + incident + investigation(`QUEUED`) + work_item в одной транзакции → `202`
- [ ] Повтор `(source, eventId)` возвращает тот же `incidentId` и не создаёт дубликатов
- [ ] `POST /api/v1/incidents` (ручной ввод) и `GET /api/v1/incidents/{id}`
- [ ] Секреты маскируются до сохранения

## Фаза 3 — queue
- [ ] Worker забирает задания через `FOR UPDATE SKIP LOCKED` + lease
- [ ] Не больше 3 попыток, затем `FAILED`
- [ ] Тест: задание переживает рестарт worker

## Фаза 4 — simulator
- [ ] Fixtures `DB_POOL_EXHAUSTED` и скрытый `expected.json`
- [ ] Tools `get_logs`, `get_metrics`, `get_trace`, `get_database_activity` поверх fixtures
- [ ] Baseline сохраняет факты в `ops.evidence`
- [ ] `POST /api/v1/simulator/scenarios/{id}/run`

## Фаза 5a — orchestrator
- [ ] `AiDecision`, порт `LlmGateway`, `ScriptedLlmGateway`
- [ ] Цикл ≤ N итераций с вызовом tools
- [ ] Gates `SCHEMA` и `EVIDENCE`: несуществующий `evidenceId` отклоняется

## Фаза 5b — report
- [ ] Итог `DIAGNOSED` / `INCONCLUSIVE` / `FAILED` с сохранением отчёта
- [ ] `GET /api/v1/incidents/{id}/report`
- [ ] Eval-тест: категория совпадает с эталоном, эталон не утекает

## Фаза 6 — gigachat
- [ ] Спайк: токен, TLS, function calling, embeddings; выводы — в `tech-stack.md`
- [ ] `GigaChatLlmGateway`
- [ ] Ручной прогон сценария на реальной модели

## Фаза 7a — knowledge
- [ ] 5–10 документов в `src/main/resources/knowledge/` (front-matter: product, version, status)
- [ ] Loader: документ → chunks + embeddings
- [ ] Полнотекстовый поиск (FTS) по chunks

## Фаза 7b — rag
- [ ] Hybrid search: FTS + vector, фильтр версии и `APPROVED`
- [ ] Tool `search_knowledge` и первичный retrieval в контексте LLM
- [ ] Тест: неутверждённые документы и документы чужой версии не выдаются

## Фаза 8 — mcp
- [ ] Локальный MCP-сервер — обёртка над `DiagnosticToolPort`, только read-only tools
- [ ] Те же проверки аргументов, лимитов и маскировки, что у function calling
- [ ] Тест: MCP-клиент вызывает `get_logs` и получает тот же результат, что и через порт

---

Ориентир — 7–9 сессий по 2 часа. Если не хватает времени: GigaChat заменяется `ScriptedLlmGateway`, RAG работает только на FTS.
