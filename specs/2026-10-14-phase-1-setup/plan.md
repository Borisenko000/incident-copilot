# Фаза 1 — setup: план

Каждая группа — небольшой шаг с diff, объяснением и коммитом (после подтверждения). Лекции проводятся до первого кода группы.

## 1. Каркас и сборка

Лекции: модульный монолит и hexagonal; работа с AI co-pilot.

1.1. `pom.xml`: parent `spring-boot-starter-parent:4.1.1`, Java 21, стартеры `webmvc`, `jdbc`, `flyway`, `actuator`; `flyway-database-postgresql`, `postgresql` (runtime); тестовые зависимости — `spring-boot-starter-test`, `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`, ArchUnit.
1.2. Удалить `dev.polina.Main`, создать `dev.polina.copilot.IncidentCopilotApplication`.
1.3. `.gitignore`: `target/`, `.idea/`.
1.4. Проверка: `mvn compile` проходит. Если Boot 4 не собирается — откат на 3.5.x и запись в «Отклонения».

## 2. Docker Compose и подключение к БД

Лекция: Docker Compose и PostgreSQL.

2.1. `docker-compose.yml`: `pgvector/pgvector:pg16`, порт 5432, volume, healthcheck `pg_isready`.
2.2. `src/main/resources/application.yml`: datasource через переменные окружения с локальными значениями по умолчанию; actuator — открыт только `health`, `show-components: always`.
2.3. Ручная проверка: `docker compose up -d` → приложение стартует.

## 3. Миграция V1 (тест сначала)

Лекции: Flyway и версионирование схемы; pgvector.

3.1. Тестовая конфигурация Testcontainers (`@ServiceConnection`, образ `pgvector/pgvector:pg16`).
3.2. Интеграционный тест схемы — падает (миграции ещё нет): 5 таблиц в `ops`, 2 в `knowledge`, расширение `vector`, UNIQUE `(source, event_id)`, CHECK отклоняет недопустимый статус, вставка вектора длины 1024 проходит, другой длины — отклоняется.
3.3. `src/main/resources/db/migration/V1__init.sql` по requirements.md → тест зелёный.

## 4. Health и границы архитектуры

4.1. Тест: `GET /actuator/health` → `200`, `status=UP`, компонент `db` = `UP`.
4.2. ArchUnit-тест: `domain`/`application` не зависят от Spring, Spring AI, JDBC, GigaChat.
4.3. `mvn test` зелёный целиком.

## 5. Завершение фазы

5.1. Пройти `validation.md`, включая ручную проверку.
5.2. Отметить пункты фазы 1 в `specs/roadmap.md`; заполнить «Отклонения» в `requirements.md`.
5.3. Записать фактические версии ArchUnit и Testcontainers в `specs/tech-stack.md`.
5.4. Показать итоговый diff, согласовать push и PR.
