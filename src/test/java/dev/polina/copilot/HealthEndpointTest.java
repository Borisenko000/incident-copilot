package dev.polina.copilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** Приложение стартует на настоящем порту и сообщает, что оно и БД живы. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class HealthEndpointTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("GET /actuator/health → 200, UP, компонент db = UP")
    @SuppressWarnings("unchecked")
    void healthIsUpWithDatabase() {
        ResponseEntity<Map> response = client().get().uri("/actuator/health")
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("status", "UP");
        Map<String, Object> components = (Map<String, Object>) response.getBody().get("components");
        assertThat((Map<String, Object>) components.get("db")).containsEntry("status", "UP");
    }

    @Test
    @DisplayName("остальные actuator endpoints закрыты: /actuator/env → 404")
    void otherEndpointsAreHidden() {
        HttpStatusCode status = client().get().uri("/actuator/env")
                .exchange((request, response) -> response.getStatusCode());

        assertThat(status.value()).isEqualTo(404);
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }
}
