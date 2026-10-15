package dev.polina.copilot.application;

import dev.polina.copilot.domain.Fingerprint;
import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.IncidentStatus;
import dev.polina.copilot.domain.InvestigationStatus;
import dev.polina.copilot.domain.Severity;
import dev.polina.copilot.port.IncidentRepository;
import dev.polina.copilot.port.IncidentRepository.NewIncident;
import dev.polina.copilot.port.IncidentRepository.OpenIncident;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Use case приёма события (FR-02, FR-04). Весь метод должен выполняться в одной транзакции —
 * её открывает адаптер: здесь, в application, Spring недоступен (NFR-01).
 */
public final class IngestEventService {

    static final String INVESTIGATE = "INVESTIGATE";

    private final IncidentRepository repository;
    private final EventSanitizer sanitizer;
    private final Clock clock;
    private final Duration correlationWindow;

    public IngestEventService(IncidentRepository repository, EventSanitizer sanitizer, Clock clock,
                              Duration correlationWindow) {
        this.repository = repository;
        this.sanitizer = sanitizer;
        this.clock = clock;
        this.correlationWindow = correlationWindow;
    }

    public IngestResult ingest(IncidentEvent rawEvent) {
        IncidentEvent event = sanitizer.sanitize(rawEvent); // до любой записи
        Fingerprint fingerprint = Fingerprint.of(event);

        repository.lockFingerprint(fingerprint);

        Optional<UUID> knownIncident = repository.findIncidentIdByEvent(event.source(), event.eventId());
        if (knownIncident.isPresent()) {
            UUID incidentId = knownIncident.get();
            return new IngestResult(incidentId, event.eventId(), repository.latestInvestigationStatus(incidentId), true);
        }

        Instant now = clock.instant();
        Optional<OpenIncident> open = repository.findOpenIncident(fingerprint, now.minus(correlationWindow));
        if (open.isPresent()) {
            return attachToIncident(open.get(), event, now);
        }
        return openNewIncident(event, fingerprint, now);
    }

    /**
     * Ручной ввод (FR-03): всегда новый инцидент со своим расследованием, без корреляции —
     * человек явно просит разобраться. Fingerprint уникален по eventId ({@link Fingerprint#forManual}),
     * поэтому авто-события к ручному инциденту тоже не привязываются. Lock и проверка дублей не нужны:
     * eventId — свежий серверный UUID, ключ ни с кем не совпадает.
     */
    public IngestResult openManualIncident(IncidentEvent rawEvent) {
        IncidentEvent event = sanitizer.sanitizeManual(rawEvent);
        return openNewIncident(event, Fingerprint.forManual(event), clock.instant());
    }

    /** Коррелированное событие: без нового расследования, чтобы шторм ошибок не превратился в шторм вызовов LLM. */
    private IngestResult attachToIncident(OpenIncident incident, IncidentEvent event, Instant now) {
        Severity severity = max(incident.severity(), event.severity());
        repository.updateIncidentActivity(incident.id(), severity, now);
        repository.insertEvent(UUID.randomUUID(), incident.id(), event, now);
        return new IngestResult(incident.id(), event.eventId(), repository.latestInvestigationStatus(incident.id()), false);
    }

    private IngestResult openNewIncident(IncidentEvent event, Fingerprint fingerprint, Instant now) {
        UUID incidentId = UUID.randomUUID();
        UUID investigationId = UUID.randomUUID();

        repository.insertIncident(new NewIncident(incidentId, event.service(), event.environment(), event.type(),
                event.severity(), fingerprint, IncidentStatus.OPEN, now));
        repository.insertEvent(UUID.randomUUID(), incidentId, event, now);
        repository.insertInvestigation(investigationId, incidentId, 1, InvestigationStatus.QUEUED);
        repository.insertWorkItem(UUID.randomUUID(), incidentId, investigationId, INVESTIGATE,
                "investigate:" + investigationId);

        return new IngestResult(incidentId, event.eventId(), InvestigationStatus.QUEUED, false);
    }

    private static Severity max(Severity a, Severity b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
