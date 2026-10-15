package dev.polina.copilot.adapter.out.persistence;

import static dev.polina.copilot.domain.TestEvents.event;
import static org.assertj.core.api.Assertions.assertThat;

import dev.polina.copilot.AbstractIntegrationTest;
import dev.polina.copilot.domain.Fingerprint;
import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.IncidentStatus;
import dev.polina.copilot.domain.InvestigationStatus;
import dev.polina.copilot.domain.Severity;
import dev.polina.copilot.port.IncidentRepository;
import dev.polina.copilot.port.IncidentRepository.IncidentCard;
import dev.polina.copilot.port.IncidentRepository.NewIncident;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** SQL адаптера на настоящей PostgreSQL. Каждый тест откатывается. */
@Transactional
class JdbcIncidentRepositoryTest extends AbstractIntegrationTest {

    // PostgreSQL хранит микросекунды — округляем, чтобы сравнение было точным
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    IncidentRepository repository;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("открытый инцидент находится, только пока updated_at в окне")
    void findsOpenIncidentWithinWindow() {
        Fingerprint fp = uniqueFingerprint();
        UUID id = insertIncident(fp, NOW.minus(Duration.ofMinutes(10)));

        assertThat(repository.findOpenIncident(fp, NOW.minus(Duration.ofMinutes(30))))
                .hasValueSatisfying(open -> {
                    assertThat(open.id()).isEqualTo(id);
                    assertThat(open.severity()).isEqualTo(Severity.HIGH);
                });
        assertThat(repository.findOpenIncident(fp, NOW.minus(Duration.ofMinutes(5)))).isEmpty();
    }

    @Test
    @DisplayName("обновление активности сдвигает окно и меняет severity")
    void updateActivityMovesWindow() {
        Fingerprint fp = uniqueFingerprint();
        UUID id = insertIncident(fp, NOW.minus(Duration.ofHours(1)));

        repository.updateIncidentActivity(id, Severity.CRITICAL, NOW);

        assertThat(repository.findOpenIncident(fp, NOW.minus(Duration.ofMinutes(1))))
                .hasValueSatisfying(open -> assertThat(open.severity()).isEqualTo(Severity.CRITICAL));
    }

    @Test
    @DisplayName("событие ищется по (source, eventId), payload сохраняется как jsonb")
    void storesEventWithJsonPayload() {
        UUID incidentId = insertIncident(uniqueFingerprint(), NOW);
        IncidentEvent event = event().eventId("evt-" + UUID.randomUUID())
                .attributes(Map.of("topic", "orders")).build();

        repository.insertEvent(UUID.randomUUID(), incidentId, event, NOW);

        assertThat(repository.findIncidentIdByEvent(event.source(), event.eventId())).contains(incidentId);
        assertThat(repository.findIncidentIdByEvent("other-source", event.eventId())).isEmpty();
        String topic = jdbc.sql("""
                        SELECT payload -> 'attributes' ->> 'topic' FROM ops.incident_events
                        WHERE source = ? AND event_id = ?
                        """)
                .params(event.source(), event.eventId()).query(String.class).single();
        assertThat(topic).isEqualTo("orders");
    }

    @Test
    @DisplayName("карточка считает события и берёт последнюю версию расследования")
    void cardCountsEventsAndTakesLatestInvestigation() {
        UUID incidentId = insertIncident(uniqueFingerprint(), NOW);
        repository.insertEvent(UUID.randomUUID(), incidentId, event().eventId("a-" + UUID.randomUUID()).build(), NOW);
        repository.insertEvent(UUID.randomUUID(), incidentId, event().eventId("b-" + UUID.randomUUID()).build(), NOW);
        repository.insertInvestigation(UUID.randomUUID(), incidentId, 1, InvestigationStatus.FAILED);
        UUID latest = UUID.randomUUID();
        repository.insertInvestigation(latest, incidentId, 2, InvestigationStatus.QUEUED);

        IncidentCard card = repository.findCard(incidentId).orElseThrow();

        assertThat(card.eventCount()).isEqualTo(2);
        assertThat(card.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(card.createdAt()).isEqualTo(NOW);
        assertThat(card.latestInvestigation().id()).isEqualTo(latest);
        assertThat(card.latestInvestigation().version()).isEqualTo(2);
        assertThat(repository.latestInvestigationStatus(incidentId)).isEqualTo(InvestigationStatus.QUEUED);
    }

    @Test
    @DisplayName("несуществующий инцидент → пустая карточка")
    void cardOfUnknownIncidentIsEmpty() {
        assertThat(repository.findCard(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("задание пишется со статусом READY и dedupe_key")
    void insertsReadyWorkItem() {
        UUID incidentId = insertIncident(uniqueFingerprint(), NOW);
        UUID investigationId = UUID.randomUUID();
        repository.insertInvestigation(investigationId, incidentId, 1, InvestigationStatus.QUEUED);

        repository.insertWorkItem(UUID.randomUUID(), incidentId, investigationId, "INVESTIGATE", "investigate:" + investigationId);

        String status = jdbc.sql("SELECT status FROM ops.work_items WHERE dedupe_key = ?")
                .param("investigate:" + investigationId).query(String.class).single();
        assertThat(status).isEqualTo("READY");
    }

    @Test
    @DisplayName("advisory lock берётся внутри транзакции без ошибок")
    void lockFingerprintWorksInTransaction() {
        repository.lockFingerprint(uniqueFingerprint());
    }

    private UUID insertIncident(Fingerprint fp, Instant at) {
        UUID id = UUID.randomUUID();
        repository.insertIncident(new NewIncident(id, "order-service", "staging", "DB_POOL_EXHAUSTED",
                Severity.HIGH, fp, IncidentStatus.OPEN, at));
        return id;
    }

    private static Fingerprint uniqueFingerprint() {
        return new Fingerprint("fp-" + UUID.randomUUID());
    }
}
