package dev.polina.copilot.port;

import dev.polina.copilot.domain.Fingerprint;
import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.IncidentStatus;
import dev.polina.copilot.domain.InvestigationStatus;
import dev.polina.copilot.domain.Severity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище инцидентов (схема ops). Методы вызываются внутри транзакции,
 * которую открывает адаптер; сам порт о транзакциях не знает.
 */
public interface IncidentRepository {

    /** Блокировка до конца транзакции: события с одним fingerprint обрабатываются по очереди. */
    void lockFingerprint(Fingerprint fingerprint);

    Optional<UUID> findIncidentIdByEvent(String source, String eventId);

    /** Открытый инцидент с этим fingerprint, активный не раньше {@code activeSince}. */
    Optional<OpenIncident> findOpenIncident(Fingerprint fingerprint, Instant activeSince);

    InvestigationStatus latestInvestigationStatus(UUID incidentId);

    void insertIncident(NewIncident incident);

    void updateIncidentActivity(UUID incidentId, Severity severity, Instant updatedAt);

    void insertEvent(UUID id, UUID incidentId, IncidentEvent event, Instant receivedAt);

    void insertInvestigation(UUID id, UUID incidentId, int version, InvestigationStatus status);

    void insertWorkItem(UUID id, UUID incidentId, UUID investigationId, String kind, String dedupeKey);

    Optional<IncidentCard> findCard(UUID incidentId);

    record OpenIncident(UUID id, Severity severity) {
    }

    record NewIncident(UUID id, String service, String environment, String category, Severity severity,
                       Fingerprint fingerprint, IncidentStatus status, Instant createdAt) {
    }

    record IncidentCard(UUID id, String service, String environment, String category, Severity severity,
                        IncidentStatus status, Instant createdAt, Instant updatedAt, long eventCount,
                        LatestInvestigation latestInvestigation) {
    }

    record LatestInvestigation(UUID id, int version, InvestigationStatus status) {
    }
}
