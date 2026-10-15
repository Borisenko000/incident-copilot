package dev.polina.copilot.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.polina.copilot.AbstractIntegrationTest;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** POST /api/v1/events через настоящий HTTP и настоящую БД: запросы коммитятся, поэтому таблицы чистятся перед каждым тестом. */
class EventIngestionApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE ops.work_items, ops.evidence, ops.investigations, ops.incident_events, ops.incidents CASCADE")
                .update();
    }

    @Test
    @DisplayName("новое событие → 202, Location и по одной строке в 4 таблицах")
    void acceptsNewEvent() {
        Response response = post(event("evt-1"));

        assertThat(response.status()).isEqualTo(202);
        String incidentId = (String) response.body().get("incidentId");
        assertThat(response.location()).hasToString("/api/v1/incidents/" + incidentId);
        assertThat(response.body())
                .containsEntry("eventId", "evt-1")
                .containsEntry("investigationStatus", "QUEUED")
                .containsEntry("deduplicated", false);

        assertThat(count("ops.incidents WHERE status = 'OPEN'")).isEqualTo(1);
        assertThat(count("ops.incident_events")).isEqualTo(1);
        assertThat(count("ops.investigations WHERE status = 'QUEUED' AND version = 1")).isEqualTo(1);
        assertThat(count("ops.work_items WHERE status = 'READY' AND kind = 'INVESTIGATE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("повтор (source, eventId) → тот же incidentId, deduplicated=true, новых строк нет")
    void duplicateEventIsIdempotent() {
        Response first = post(event("evt-1"));

        Response second = post(event("evt-1"));

        assertThat(second.status()).isEqualTo(202);
        assertThat(second.body())
                .containsEntry("incidentId", first.body().get("incidentId"))
                .containsEntry("deduplicated", true);
        assertThat(rowCounts()).containsExactly(1L, 1L, 1L, 1L);
    }

    @Test
    @DisplayName("секреты из запроса в БД хранятся замаскированными")
    void storesMaskedPayload() {
        Map<String, Object> event = event("evt-1");
        event.put("error", Map.of("className", "java.sql.SQLException",
                "message", "FATAL: password authentication failed, password=hunter2",
                "stackTrace", "at jdbc:postgresql://app:s3cret@db:5432/orders"));

        post(event);

        String payload = jdbc.sql("SELECT payload::text FROM ops.incident_events").query(String.class).single();
        assertThat(payload).doesNotContain("hunter2", "s3cret").contains("password=***", "app:***@db");
    }

    @Test
    @DisplayName("другое событие с тем же fingerprint → тот же инцидент, updated_at сдвинут, расследование одно")
    void correlatedEventJoinsIncident() {
        Response first = post(event("evt-1"));
        OffsetDateTime updatedBefore = updatedAt();

        Response second = post(event("evt-2"));

        assertThat(second.body())
                .containsEntry("incidentId", first.body().get("incidentId"))
                .containsEntry("deduplicated", false);
        assertThat(rowCounts()).containsExactly(1L, 2L, 1L, 1L);
        assertThat(updatedAt()).isAfter(updatedBefore);
    }

    @Test
    @DisplayName("сбой при INSERT задания → откат всего: ни события, ни инцидента, ни расследования")
    void failureRollsBackEverything() {
        // Триггер заставляет PostgreSQL отклонить последний INSERT транзакции — проверяем откат на настоящей БД
        jdbc.sql("""
                CREATE FUNCTION ops.test_fail_work_item() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'test: work item insert failed'; END
                $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("""
                CREATE TRIGGER test_fail_work_item BEFORE INSERT ON ops.work_items
                FOR EACH ROW EXECUTE FUNCTION ops.test_fail_work_item()
                """).update();
        try {
            Response response = post(event("evt-1"));

            assertThat(response.status()).isEqualTo(500);
            assertThat(response.contentType()).isEqualTo("application/problem+json");
            assertThat((String) response.body().get("detail")).isEqualTo("Internal error"); // без SQL и деталей
            assertThat(rowCounts()).containsExactly(0L, 0L, 0L, 0L);
        } finally {
            jdbc.sql("DROP TRIGGER test_fail_work_item ON ops.work_items").update();
            jdbc.sql("DROP FUNCTION ops.test_fail_work_item()").update();
        }
    }

    @Test
    @DisplayName("одно и то же событие параллельно из двух потоков → одна запись, один incidentId")
    void concurrentDuplicatesStoreOneEvent() throws Exception {
        List<Response> responses = inParallel(() -> post(event("evt-1")), () -> post(event("evt-1")));

        assertThat(responses).extracting(Response::status).containsOnly(202);
        assertThat(responses).extracting(r -> r.body().get("incidentId")).containsOnly(responses.getFirst().body().get("incidentId"));
        assertThat(rowCounts()).containsExactly(1L, 1L, 1L, 1L);
    }

    @Test
    @DisplayName("тот же eventId с разным содержимым параллельно → UNIQUE + повтор, всё равно одна запись")
    void concurrentSameEventIdDifferentFingerprint() throws Exception {
        Map<String, Object> other = event("evt-1");
        other.put("service", "payment-service"); // другой fingerprint → другой advisory lock, гонку ловит UNIQUE

        List<Response> responses = inParallel(() -> post(event("evt-1")), () -> post(other));

        assertThat(responses).extracting(Response::status).containsOnly(202);
        assertThat(responses).extracting(r -> r.body().get("incidentId")).containsOnly(responses.getFirst().body().get("incidentId"));
        assertThat(count("ops.incident_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("разные события с одним fingerprint параллельно → один инцидент (advisory lock)")
    void concurrentCorrelatedEventsShareIncident() throws Exception {
        List<Response> responses = inParallel(() -> post(event("evt-1")), () -> post(event("evt-2")));

        assertThat(responses).extracting(Response::status).containsOnly(202);
        assertThat(rowCounts()).containsExactly(1L, 2L, 1L, 1L);
    }

    @Test
    @DisplayName("невалидный JSON → 400 ProblemDetail, ничего не сохранено")
    void malformedJsonIs400() {
        Response response = postRaw("{\"eventId\": ");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.contentType()).isEqualTo("application/problem+json");
        assertThat(rowCounts()).containsExactly(0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("тело больше 16 KB → 413 ProblemDetail, ничего не сохранено")
    void oversizedBodyIs413() {
        Map<String, Object> event = event("evt-1");
        event.put("summary", "x".repeat(17 * 1024));

        Response response = post(event);

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.contentType()).isEqualTo("application/problem+json");
        assertThat(rowCounts()).containsExactly(0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("нет обязательного поля → 422 с именем поля")
    void missingFieldIs422() {
        Map<String, Object> event = event("evt-1");
        event.remove("service");

        Response response = post(event);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.contentType()).isEqualTo("application/problem+json");
        assertThat((String) response.body().get("detail")).contains("service");
        assertThat(rowCounts()).containsExactly(0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("поле только из Unicode-пробела (em space) → 422, а не 500")
    void unicodeBlankFieldIs422() {
        Map<String, Object> event = event("evt-1");
        event.put("service", " ");

        Response response = post(event);

        assertThat(response.status()).isEqualTo(422);
        assertThat((String) response.body().get("detail")).contains("service");
        assertThat(rowCounts()).containsExactly(0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("лимит 16 KB не обходится параметром пути: /api;x=1/v1/events → 413")
    void sizeLimitCannotBeBypassedByPathParameter() {
        Map<String, Object> event = event("evt-1");
        event.put("summary", "x".repeat(17 * 1024));

        Response response = postRaw("/api;x=1/v1/events", json.writeValueAsString(event));

        assertThat(response.status()).isEqualTo(413);
        assertThat(rowCounts()).containsExactly(0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("неизвестная severity → 422")
    void unknownSeverityIs422() {
        Map<String, Object> event = event("evt-1");
        event.put("severity", "APOCALYPTIC");

        Response response = post(event);

        assertThat(response.status()).isEqualTo(422);
        assertThat((String) response.body().get("detail")).contains("severity");
    }

    // ---------- помощники ----------

    private static Map<String, Object> event(String eventId) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", eventId);
        event.put("source", "simulator");
        event.put("occurredAt", "2026-10-15T10:00:00Z");
        event.put("service", "order-service");
        event.put("environment", "staging");
        event.put("type", "DB_POOL_EXHAUSTED");
        event.put("severity", "HIGH");
        event.put("summary", "Connection is not available");
        event.put("error", Map.of("className", "java.sql.SQLTransientConnectionException",
                "message", "HikariPool-1 - Connection is not available, request timed out after 30000ms"));
        return event;
    }

    private Response post(Map<String, Object> event) {
        return postRaw(json.writeValueAsString(event));
    }

    private Response postRaw(String body) {
        return postRaw("/api/v1/events", body);
    }

    @SuppressWarnings("unchecked")
    private Response postRaw(String path, String body) {
        return RestClient.create("http://localhost:" + port).post().uri(URI.create("http://localhost:" + port + path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    MediaType type = response.getHeaders().getContentType();
                    return new Response(response.getStatusCode().value(), response.getHeaders().getLocation(),
                            type == null ? null : type.getType() + "/" + type.getSubtype(),
                            response.bodyTo(Map.class));
                });
    }

    @SafeVarargs
    private static List<Response> inParallel(Callable<Response>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Response>> futures = java.util.Arrays.stream(calls)
                    .map(call -> pool.submit(() -> {
                        start.await();
                        return call.call();
                    }))
                    .toList();
            start.countDown(); // все потоки стартуют одновременно
            List<Response> responses = new java.util.ArrayList<>();
            for (Future<Response> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private long count(String fromWhere) {
        return jdbc.sql("SELECT count(*) FROM " + fromWhere).query(Long.class).single();
    }

    /** Строки в incidents, incident_events, investigations, work_items. */
    private List<Long> rowCounts() {
        return List.of(count("ops.incidents"), count("ops.incident_events"),
                count("ops.investigations"), count("ops.work_items"));
    }

    private OffsetDateTime updatedAt() {
        return jdbc.sql("SELECT updated_at FROM ops.incidents").query(OffsetDateTime.class).single();
    }

    record Response(int status, URI location, String contentType, Map<String, Object> body) {
    }
}
