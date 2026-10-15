package dev.polina.copilot.adapter.in.rest;

import dev.polina.copilot.application.IngestResult;
import dev.polina.copilot.domain.InvestigationStatus;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

/** Общий ответ 202 для POST /events и POST /incidents: куда попал вход и было ли это повтором. */
record IngestAcceptedResponse(UUID incidentId, String eventId, InvestigationStatus investigationStatus,
                              boolean deduplicated) {

    /** 202 Accepted + Location на карточку инцидента. */
    static ResponseEntity<IngestAcceptedResponse> accepted(IngestResult result) {
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/incidents/" + result.incidentId()))
                .body(new IngestAcceptedResponse(result.incidentId(), result.eventId(),
                        result.investigationStatus(), result.deduplicated()));
    }
}
