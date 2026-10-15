package dev.polina.copilot.application;

import dev.polina.copilot.domain.Fingerprint;
import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.IncidentStatus;
import dev.polina.copilot.domain.InvestigationStatus;
import dev.polina.copilot.domain.Severity;
import dev.polina.copilot.port.IncidentRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Fake порта для юнит-тестов: те же правила выборки, что и SQL, но в памяти. Записывает порядок вызовов. */
final class InMemoryIncidentRepository implements IncidentRepository {

    final Map<UUID, StoredIncident> incidents = new LinkedHashMap<>();
    final List<StoredEvent> events = new ArrayList<>();
    final List<StoredInvestigation> investigations = new ArrayList<>();
    final List<StoredWorkItem> workItems = new ArrayList<>();
    final List<String> calls = new ArrayList<>();

    @Override
    public void lockFingerprint(Fingerprint fingerprint) {
        calls.add("lock:" + fingerprint.value());
    }

    @Override
    public Optional<UUID> findIncidentIdByEvent(String source, String eventId) {
        calls.add("findEvent");
        return events.stream()
                .filter(e -> e.event().source().equals(source) && e.event().eventId().equals(eventId))
                .map(StoredEvent::incidentId)
                .findFirst();
    }

    @Override
    public Optional<OpenIncident> findOpenIncident(Fingerprint fingerprint, Instant activeSince) {
        calls.add("findOpen");
        return incidents.values().stream()
                .filter(i -> i.fingerprint().equals(fingerprint))
                .filter(i -> i.status() == IncidentStatus.OPEN)
                .filter(i -> !i.updatedAt().isBefore(activeSince))
                .max(Comparator.comparing(StoredIncident::updatedAt))
                .map(i -> new OpenIncident(i.id(), i.severity()));
    }

    @Override
    public InvestigationStatus latestInvestigationStatus(UUID incidentId) {
        return investigations.stream()
                .filter(i -> i.incidentId().equals(incidentId))
                .max(Comparator.comparingInt(StoredInvestigation::version))
                .orElseThrow()
                .status();
    }

    @Override
    public void insertIncident(NewIncident i) {
        incidents.put(i.id(), new StoredIncident(i.id(), i.service(), i.environment(), i.category(), i.severity(),
                i.fingerprint(), i.status(), i.createdAt(), i.createdAt()));
    }

    @Override
    public void updateIncidentActivity(UUID incidentId, Severity severity, Instant updatedAt) {
        StoredIncident old = incidents.get(incidentId);
        incidents.put(incidentId, old.with(severity, old.status(), updatedAt));
    }

    @Override
    public void insertEvent(UUID id, UUID incidentId, IncidentEvent event, Instant receivedAt) {
        events.add(new StoredEvent(id, incidentId, event, receivedAt));
    }

    @Override
    public void insertInvestigation(UUID id, UUID incidentId, int version, InvestigationStatus status) {
        investigations.add(new StoredInvestigation(id, incidentId, version, status));
    }

    @Override
    public void insertWorkItem(UUID id, UUID incidentId, UUID investigationId, String kind, String dedupeKey) {
        workItems.add(new StoredWorkItem(id, incidentId, investigationId, kind, dedupeKey));
    }

    @Override
    public Optional<IncidentCard> findCard(UUID incidentId) {
        throw new UnsupportedOperationException("карточка проверяется интеграционными тестами");
    }

    /** Для теста «закрытый инцидент не переиспользуется». */
    void setStatus(UUID incidentId, IncidentStatus status) {
        StoredIncident old = incidents.get(incidentId);
        incidents.put(incidentId, old.with(old.severity(), status, old.updatedAt()));
    }

    record StoredIncident(UUID id, String service, String environment, String category, Severity severity,
                          Fingerprint fingerprint, IncidentStatus status, Instant createdAt, Instant updatedAt) {
        StoredIncident with(Severity newSeverity, IncidentStatus newStatus, Instant newUpdatedAt) {
            return new StoredIncident(id, service, environment, category, newSeverity, fingerprint, newStatus,
                    createdAt, newUpdatedAt);
        }
    }

    record StoredEvent(UUID id, UUID incidentId, IncidentEvent event, Instant receivedAt) {
    }

    record StoredInvestigation(UUID id, UUID incidentId, int version, InvestigationStatus status) {
    }

    record StoredWorkItem(UUID id, UUID incidentId, UUID investigationId, String kind, String dedupeKey) {
    }
}
