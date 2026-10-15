package dev.polina.copilot.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.polina.copilot.AbstractIntegrationTest;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** POST /api/v1/incidents (ручной ввод) через настоящий HTTP и БД. */
class ManualIncidentApiTest extends AbstractIntegrationTest {

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
    @DisplayName("ручной ввод → 202, Location, 4 строки в БД, source=MANUAL, значения по умолчанию")
    void createsManualIncident() {
        Response response = post("/api/v1/incidents", manual());

        assertThat(response.status()).isEqualTo(202);
        String incidentId = (String) response.body().get("incidentId");
        assertThat(response.location()).hasToString("/api/v1/incidents/" + incidentId);
        assertThat(UUID.fromString((String) response.body().get("eventId"))).isNotNull();
        assertThat(response.body())
                .containsEntry("investigationStatus", "QUEUED")
                .containsEntry("deduplicated", false);

        assertThat(rowCounts()).containsExactly(1L, 1L, 1L, 1L);
        assertThat(jdbc.sql("SELECT source FROM ops.incident_events").query(String.class).single()).isEqualTo("MANUAL");
        assertThat(jdbc.sql("SELECT category || '/' || severity FROM ops.incidents").query(String.class).single())
                .isEqualTo("MANUAL/MEDIUM");
    }

    @Test
    @DisplayName("открытый авто-инцидент с тем же fingerprint не переиспользуется и не меняется")
    void doesNotCorrelateWithOpenIncident() {
        Map<String, Object> event = Map.of("eventId", "evt-1", "source", "simulator",
                "occurredAt", "2026-10-15T10:00:00Z", "service", "order-service", "environment", "staging",
                "type", "MANUAL", "severity", "MEDIUM");
        String autoIncident = (String) post("/api/v1/events", event).body().get("incidentId");
        OffsetDateTime autoUpdatedAt = updatedAt(autoIncident);

        String manualIncident = (String) post("/api/v1/incidents", manual()).body().get("incidentId");

        assertThat(manualIncident).isNotEqualTo(autoIncident);
        assertThat(updatedAt(autoIncident)).isEqualTo(autoUpdatedAt);
        assertThat(rowCounts()).containsExactly(2L, 2L, 2L, 2L);
    }

    @Test
    @DisplayName("одна форма дважды → два инцидента с разными eventId (Idempotency-Key — P1)")
    void sameFormTwiceCreatesTwoIncidents() {
        Response first = post("/api/v1/incidents", manual());
        Response second = post("/api/v1/incidents", manual());

        assertThat(second.body().get("incidentId")).isNotEqualTo(first.body().get("incidentId"));
        assertThat(second.body().get("eventId")).isNotEqualTo(first.body().get("eventId"));
        assertThat(count("ops.incidents")).isEqualTo(2);
    }

    @Test
    @DisplayName("секреты из title, description и errorText в БД хранятся замаскированными")
    void storesMaskedPayload() {
        Map<String, Object> form = manual();
        form.put("title", "login fails, password=hunter1");
        form.put("description", "token=abc123 from ops@example.com");
        form.put("errorText", "FATAL: auth failed\nat jdbc:postgresql://app:s3cret@db/orders");

        post("/api/v1/incidents", form);

        String payload = jdbc.sql("SELECT payload::text FROM ops.incident_events").query(String.class).single();
        assertThat(payload).doesNotContain("hunter1", "abc123", "ops@example.com", "s3cret")
                .contains("password=***", "token=***", "app:***@db");
    }

    @Test
    @DisplayName("нет errorText → 422 с именем поля, ничего не сохранено")
    void missingErrorTextIs422() {
        Map<String, Object> form = manual();
        form.remove("errorText");

        Response response = post("/api/v1/incidents", form);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.contentType()).isEqualTo("application/problem+json");
        assertThat((String) response.body().get("detail")).contains("errorText");
        assertThat(rowCounts()).containsExactly(0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("errorText из пробелов и переводов строк → 422")
    void blankErrorTextIs422() {
        Map<String, Object> form = manual();
        form.put("errorText", " \n\t\n ");

        assertThat(post("/api/v1/incidents", form).status()).isEqualTo(422);
        assertThat(rowCounts()).containsExactly(0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("service только из Unicode-пробела (em space) → 422, а не 500")
    void unicodeBlankServiceIs422() {
        Map<String, Object> form = manual();
        form.put("service", " ");

        Response response = post("/api/v1/incidents", form);

        assertThat(response.status()).isEqualTo(422);
        assertThat(rowCounts()).containsExactly(0L, 0L, 0L, 0L);
    }

    @Test
    @DisplayName("авто-событие после ручного ввода не попадает в ручной инцидент")
    void autoEventAfterManualDoesNotJoinManualIncident() {
        Map<String, Object> event = new HashMap<>(Map.of("source", "simulator",
                "occurredAt", "2026-10-15T10:00:00Z", "service", "order-service", "environment", "staging",
                "type", "MANUAL", "severity", "MEDIUM"));
        event.put("eventId", "evt-1");
        String autoIncident = (String) post("/api/v1/events", event).body().get("incidentId");
        String manualIncident = (String) post("/api/v1/incidents", manual()).body().get("incidentId");

        event.put("eventId", "evt-2");
        String next = (String) post("/api/v1/events", event).body().get("incidentId");

        assertThat(next).isEqualTo(autoIncident).isNotEqualTo(manualIncident);
    }

    @Test
    @DisplayName("неизвестная severity → 422")
    void unknownSeverityIs422() {
        Map<String, Object> form = manual();
        form.put("severity", "URGENT");

        Response response = post("/api/v1/incidents", form);

        assertThat(response.status()).isEqualTo(422);
        assertThat((String) response.body().get("detail")).contains("severity");
    }

    @Test
    @DisplayName("невалидный JSON → 400, тело > 16 KB → 413")
    void malformedAndOversizedBodies() {
        Map<String, Object> big = manual();
        big.put("errorText", "x".repeat(17 * 1024));

        assertThat(postRaw("/api/v1/incidents", "{\"title\": ").status()).isEqualTo(400);
        assertThat(post("/api/v1/incidents", big).status()).isEqualTo(413);
        assertThat(rowCounts()).containsExactly(0L, 0L, 0L, 0L);
    }

    // ---------- помощники ----------

    /** Пример README §5.2. */
    private static Map<String, Object> manual() {
        Map<String, Object> form = new HashMap<>();
        form.put("title", "После деплоя приложение отвечает HTTP 500");
        form.put("description", "Сервис order-service, ошибки на POST /orders");
        form.put("service", "order-service");
        form.put("environment", "staging");
        form.put("errorText", "SQLTransientConnectionException: Connection is not available");
        form.put("technology", Map.of("springBoot", "4.1.x", "postgresql", "16"));
        return form;
    }

    private Response post(String path, Map<String, Object> body) {
        return postRaw(path, json.writeValueAsString(body));
    }

    @SuppressWarnings("unchecked")
    private Response postRaw(String path, String body) {
        return RestClient.create("http://localhost:" + port).post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    MediaType type = response.getHeaders().getContentType();
                    return new Response(response.getStatusCode().value(), response.getHeaders().getLocation(),
                            type == null ? null : type.getType() + "/" + type.getSubtype(),
                            response.bodyTo(Map.class));
                });
    }

    private long count(String fromWhere) {
        return jdbc.sql("SELECT count(*) FROM " + fromWhere).query(Long.class).single();
    }

    /** Строки в incidents, incident_events, investigations, work_items. */
    private List<Long> rowCounts() {
        return List.of(count("ops.incidents"), count("ops.incident_events"),
                count("ops.investigations"), count("ops.work_items"));
    }

    private OffsetDateTime updatedAt(String incidentId) {
        return jdbc.sql("SELECT updated_at FROM ops.incidents WHERE id = ?::uuid")
                .param(incidentId).query(OffsetDateTime.class).single();
    }

    record Response(int status, URI location, String contentType, Map<String, Object> body) {
    }
}
