package dev.polina.copilot.application;

import static dev.polina.copilot.domain.TestEvents.event;
import static org.assertj.core.api.Assertions.assertThat;

import dev.polina.copilot.application.InMemoryIncidentRepository.StoredIncident;
import dev.polina.copilot.application.InMemoryIncidentRepository.StoredInvestigation;
import dev.polina.copilot.application.InMemoryIncidentRepository.StoredWorkItem;
import dev.polina.copilot.domain.Fingerprint;
import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.IncidentStatus;
import dev.polina.copilot.domain.InvestigationStatus;
import dev.polina.copilot.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IngestEventServiceTest {

    private static final Instant START = Instant.parse("2026-10-15T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(30);

    private final InMemoryIncidentRepository repository = new InMemoryIncidentRepository();
    private final TestClock clock = new TestClock(START);
    private final IngestEventService service =
            new IngestEventService(repository, new EventSanitizer(), clock, WINDOW);

    @Test
    @DisplayName("новое событие → инцидент OPEN + событие + расследование QUEUED v1 + задание READY")
    void newEventCreatesIncidentInvestigationAndWorkItem() {
        IncidentEvent event = event().build();

        IngestResult result = service.ingest(event);

        assertThat(result.deduplicated()).isFalse();
        assertThat(result.eventId()).isEqualTo("evt-1");
        assertThat(result.investigationStatus()).isEqualTo(InvestigationStatus.QUEUED);

        StoredIncident incident = repository.incidents.get(result.incidentId());
        assertThat(incident.status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.category()).isEqualTo("DB_POOL_EXHAUSTED");
        assertThat(incident.severity()).isEqualTo(Severity.HIGH);
        assertThat(incident.fingerprint()).isEqualTo(Fingerprint.of(event));
        assertThat(incident.createdAt()).isEqualTo(START);

        assertThat(repository.events).singleElement()
                .satisfies(e -> assertThat(e.incidentId()).isEqualTo(result.incidentId()));
        StoredInvestigation investigation = repository.investigations.getFirst();
        assertThat(repository.investigations).hasSize(1);
        assertThat(investigation.incidentId()).isEqualTo(result.incidentId());
        assertThat(investigation.version()).isEqualTo(1);
        assertThat(investigation.status()).isEqualTo(InvestigationStatus.QUEUED);

        StoredWorkItem workItem = repository.workItems.getFirst();
        assertThat(repository.workItems).hasSize(1);
        assertThat(workItem.kind()).isEqualTo("INVESTIGATE");
        assertThat(workItem.incidentId()).isEqualTo(result.incidentId());
        assertThat(workItem.investigationId()).isEqualTo(investigation.id());
        assertThat(workItem.dedupeKey()).isEqualTo("investigate:" + investigation.id());
    }

    @Test
    @DisplayName("повтор (source, eventId) → тот же incidentId, deduplicated, ни одной новой записи")
    void duplicateEventReturnsSameIncidentWithoutWrites() {
        IngestResult first = service.ingest(event().build());
        clock.advance(Duration.ofMinutes(1));

        IngestResult second = service.ingest(event().summary("тело повтора отличается").build());

        assertThat(second.deduplicated()).isTrue();
        assertThat(second.incidentId()).isEqualTo(first.incidentId());
        assertThat(second.investigationStatus()).isEqualTo(InvestigationStatus.QUEUED);
        assertThat(repository.incidents).hasSize(1);
        assertThat(repository.events).hasSize(1);
        assertThat(repository.investigations).hasSize(1);
        assertThat(repository.workItems).hasSize(1);
        assertThat(repository.incidents.get(first.incidentId()).updatedAt()).isEqualTo(START);
    }

    @Test
    @DisplayName("тот же eventId из другого source — это другое событие")
    void sameEventIdFromOtherSourceIsNotDuplicate() {
        service.ingest(event().source("simulator").build());

        IngestResult other = service.ingest(event().source("rest").build());

        assertThat(other.deduplicated()).isFalse();
        assertThat(repository.events).hasSize(2);
    }

    @Test
    @DisplayName("событие с тем же fingerprint в окне → привязка к инциденту, updated_at обновлён, без нового расследования")
    void correlatedEventAttachesToOpenIncident() {
        IngestResult first = service.ingest(event().eventId("evt-1").build());
        clock.advance(Duration.ofMinutes(10));

        IngestResult second = service.ingest(event().eventId("evt-2").build());

        assertThat(second.incidentId()).isEqualTo(first.incidentId());
        assertThat(second.deduplicated()).isFalse();
        assertThat(second.investigationStatus()).isEqualTo(InvestigationStatus.QUEUED);
        assertThat(repository.incidents).hasSize(1);
        assertThat(repository.events).hasSize(2);
        assertThat(repository.investigations).hasSize(1);
        assertThat(repository.workItems).hasSize(1);
        assertThat(repository.incidents.get(first.incidentId()).updatedAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("severity инцидента повышается до максимальной, но не понижается")
    void correlatedEventRaisesSeverityButNeverLowers() {
        IngestResult first = service.ingest(event().eventId("evt-1").severity(Severity.HIGH).build());
        service.ingest(event().eventId("evt-2").severity(Severity.CRITICAL).build());
        service.ingest(event().eventId("evt-3").severity(Severity.LOW).build());

        assertThat(repository.incidents.get(first.incidentId()).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("окно скользящее: события каждые 20 минут остаются в одном инциденте")
    void windowSlidesWithActivity() {
        IngestResult first = service.ingest(event().eventId("evt-1").build());
        clock.advance(Duration.ofMinutes(20));
        service.ingest(event().eventId("evt-2").build());
        clock.advance(Duration.ofMinutes(20));

        IngestResult third = service.ingest(event().eventId("evt-3").build());

        assertThat(third.incidentId()).isEqualTo(first.incidentId());
    }

    @Test
    @DisplayName("ровно на границе окна событие ещё привязывается, позже — новый инцидент")
    void windowBoundary() {
        IngestResult first = service.ingest(event().eventId("evt-1").build());
        clock.advance(WINDOW);
        IngestResult atBoundary = service.ingest(event().eventId("evt-2").build());
        clock.advance(WINDOW.plusSeconds(1));

        IngestResult afterWindow = service.ingest(event().eventId("evt-3").build());

        assertThat(atBoundary.incidentId()).isEqualTo(first.incidentId());
        assertThat(afterWindow.incidentId()).isNotEqualTo(first.incidentId());
        assertThat(repository.investigations).hasSize(2);
        assertThat(repository.workItems).hasSize(2);
    }

    @Test
    @DisplayName("инцидент RESOLVED не переиспользуется: тот же fingerprint → новый инцидент")
    void resolvedIncidentIsNotReused() {
        IngestResult first = service.ingest(event().eventId("evt-1").build());
        repository.setStatus(first.incidentId(), IncidentStatus.RESOLVED);

        IngestResult second = service.ingest(event().eventId("evt-2").build());

        assertThat(second.incidentId()).isNotEqualTo(first.incidentId());
        assertThat(second.investigationStatus()).isEqualTo(InvestigationStatus.QUEUED);
    }

    @Test
    @DisplayName("другой fingerprint → отдельный инцидент")
    void differentFingerprintCreatesSeparateIncident() {
        IngestResult orders = service.ingest(event().eventId("evt-1").service("order-service").build());

        IngestResult payments = service.ingest(event().eventId("evt-2").service("payment-service").build());

        assertThat(payments.incidentId()).isNotEqualTo(orders.incidentId());
        assertThat(repository.incidents).hasSize(2);
    }

    @Test
    @DisplayName("в хранилище попадает уже замаскированное событие")
    void storesSanitizedEvent() {
        service.ingest(event().summary("login failed: password=hunter2").build());

        assertThat(repository.events.getFirst().event().summary()).isEqualTo("login failed: password=***");
    }

    // ---------- ручной ввод (фаза 2b) ----------

    @Test
    @DisplayName("ручной ввод → инцидент OPEN + событие + расследование QUEUED v1 + задание READY")
    void manualIncidentCreatesFullSet() {
        IncidentEvent manual = event().source("MANUAL").eventId("manual-1").build();

        IngestResult result = service.openManualIncident(manual);

        assertThat(result.deduplicated()).isFalse();
        assertThat(result.investigationStatus()).isEqualTo(InvestigationStatus.QUEUED);
        assertThat(repository.incidents.get(result.incidentId()).status()).isEqualTo(IncidentStatus.OPEN);
        assertThat(repository.events).singleElement()
                .satisfies(e -> assertThat(e.event().source()).isEqualTo("MANUAL"));
        assertThat(repository.investigations).singleElement()
                .satisfies(i -> assertThat(i.version()).isEqualTo(1));
        assertThat(repository.workItems).singleElement()
                .satisfies(w -> assertThat(w.dedupeKey()).isEqualTo("investigate:" + repository.investigations.getFirst().id()));
    }

    @Test
    @DisplayName("ручной ввод не коррелирует: открытый инцидент с тем же fingerprint не трогается")
    void manualIncidentIgnoresOpenIncidentWithSameFingerprint() {
        IngestResult auto = service.ingest(event().eventId("evt-1").build());
        clock.advance(Duration.ofMinutes(5));

        IngestResult manual = service.openManualIncident(event().source("MANUAL").eventId("manual-1").build());

        assertThat(manual.incidentId()).isNotEqualTo(auto.incidentId());
        assertThat(repository.incidents.get(auto.incidentId()).updatedAt()).isEqualTo(START);
        assertThat(repository.events).filteredOn(e -> e.incidentId().equals(auto.incidentId())).hasSize(1);
        assertThat(repository.investigations).hasSize(2);
        assertThat(repository.workItems).hasSize(2);
    }

    @Test
    @DisplayName("изоляция в обе стороны: авто-событие после ручного ввода идёт в авто-инцидент, а не в ручной")
    void autoEventDoesNotJoinManualIncident() {
        IngestResult auto = service.ingest(event().eventId("evt-1").build());
        clock.advance(Duration.ofMinutes(1));
        IngestResult manual = service.openManualIncident(event().source("MANUAL").eventId("manual-1").build());
        clock.advance(Duration.ofMinutes(1));

        IngestResult next = service.ingest(event().eventId("evt-2").build());

        assertThat(next.incidentId()).isEqualTo(auto.incidentId()).isNotEqualTo(manual.incidentId());
    }

    @Test
    @DisplayName("если открыт только ручной инцидент, авто-событие создаёт свой инцидент")
    void autoEventCreatesOwnIncidentWhenOnlyManualIsOpen() {
        IngestResult manual = service.openManualIncident(event().source("MANUAL").eventId("manual-1").build());

        IngestResult auto = service.ingest(event().eventId("evt-1").build());

        assertThat(auto.incidentId()).isNotEqualTo(manual.incidentId());
    }

    @Test
    @DisplayName("ручной ввод маскируется до сохранения — включая type, service и environment, которые вводит человек")
    void manualIncidentIsSanitized() {
        service.openManualIncident(event().source("MANUAL").summary("db password=hunter2")
                .type("token=abc123").service("svc secret=xyz").environment("pwd: qwerty").build());

        IncidentEvent stored = repository.events.getFirst().event();
        assertThat(stored.summary()).isEqualTo("db password=***");
        assertThat(stored.type()).isEqualTo("token=***");
        assertThat(stored.service()).isEqualTo("svc secret=***");
        assertThat(stored.environment()).isEqualTo("pwd: ***");
        assertThat(repository.incidents.values()).singleElement()
                .satisfies(i -> assertThat(i.category()).isEqualTo("token=***"));
    }

    @Test
    @DisplayName("блокировка по fingerprint берётся до любых чтений")
    void locksFingerprintBeforeReadingState() {
        IncidentEvent event = event().build();

        service.ingest(event);

        assertThat(repository.calls).startsWith("lock:" + Fingerprint.of(event).value(), "findEvent", "findOpen");
    }
}
