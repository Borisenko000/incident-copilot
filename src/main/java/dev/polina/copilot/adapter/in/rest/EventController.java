package dev.polina.copilot.adapter.in.rest;

import dev.polina.copilot.application.IngestResult;
import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.InvestigationStatus;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/v1/events: 202 после COMMIT; повтор того же события — тот же ответ с deduplicated=true. */
@RestController
@RequestMapping("/api/v1")
class EventController {

    private final TransactionalIngestion ingestion;

    EventController(TransactionalIngestion ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/events")
    ResponseEntity<EventAcceptedResponse> accept(@Valid @RequestBody EventRequest request) {
        IncidentEvent event = request.toDomain();
        IngestResult result;
        try {
            result = ingestion.ingest(event);
        } catch (DuplicateKeyException race) {
            result = ingestion.ingest(event);
        }
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/incidents/" + result.incidentId()))
                .body(new EventAcceptedResponse(result.incidentId(), result.eventId(),
                        result.investigationStatus(), result.deduplicated()));
    }

    record EventAcceptedResponse(UUID incidentId, String eventId, InvestigationStatus investigationStatus,
                                 boolean deduplicated) {
    }
}
