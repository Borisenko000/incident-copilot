package dev.polina.copilot.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.polina.copilot.AbstractIntegrationTest;
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

class IncidentQueryApiTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE ops.work_items, ops.evidence, ops.investigations, ops.incident_events, ops.incidents CASCADE")
                .update();
    }

    @Test
    @DisplayName("после двух событий карточка: поля инцидента, eventCount=2, расследование QUEUED v1")
    @SuppressWarnings("unchecked")
    void returnsIncidentCard() {
        String incidentId = postEvent("evt-1");
        postEvent("evt-2");

        Response response = get("/api/v1/incidents/" + incidentId);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body())
                .containsEntry("id", incidentId)
                .containsEntry("service", "order-service")
                .containsEntry("environment", "staging")
                .containsEntry("category", "DB_POOL_EXHAUSTED")
                .containsEntry("severity", "HIGH")
                .containsEntry("status", "OPEN")
                .containsEntry("eventCount", 2)
                .containsKeys("createdAt", "updatedAt");
        Map<String, Object> latest = (Map<String, Object>) response.body().get("latestInvestigation");
        assertThat(latest).containsEntry("version", 1).containsEntry("status", "QUEUED").containsKey("id");
    }

    @Test
    @DisplayName("неизвестный инцидент → 404 ProblemDetail")
    void unknownIncidentIs404() {
        Response response = get("/api/v1/incidents/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.contentType()).isEqualTo("application/problem+json");
    }

    @Test
    @DisplayName("id не UUID → 400 ProblemDetail")
    void invalidIdIs400() {
        Response response = get("/api/v1/incidents/not-a-uuid");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.contentType()).isEqualTo("application/problem+json");
    }

    @SuppressWarnings("unchecked")
    private String postEvent(String eventId) {
        Map<String, Object> body = client().post().uri("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"eventId": "%s", "source": "simulator", "occurredAt": "2026-10-15T10:00:00Z",
                         "service": "order-service", "environment": "staging", "type": "DB_POOL_EXHAUSTED",
                         "severity": "HIGH", "error": {"className": "java.sql.SQLTransientConnectionException"}}
                        """.formatted(eventId))
                .retrieve()
                .body(Map.class);
        return (String) body.get("incidentId");
    }

    @SuppressWarnings("unchecked")
    private Response get(String uri) {
        return client().get().uri(uri).exchange((request, response) -> {
            MediaType type = response.getHeaders().getContentType();
            return new Response(response.getStatusCode().value(),
                    type == null ? null : type.getType() + "/" + type.getSubtype(),
                    response.bodyTo(Map.class));
        });
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    record Response(int status, String contentType, Map<String, Object> body) {
    }
}
