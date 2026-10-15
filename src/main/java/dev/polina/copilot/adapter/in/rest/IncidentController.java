package dev.polina.copilot.adapter.in.rest;

import dev.polina.copilot.application.IncidentQueryService;
import dev.polina.copilot.domain.IncidentStatus;
import dev.polina.copilot.domain.InvestigationStatus;
import dev.polina.copilot.domain.Severity;
import dev.polina.copilot.port.IncidentRepository.IncidentCard;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** GET /api/v1/incidents/{id}: карточка инцидента. Нет инцидента → 404, id не UUID → 400 (оба — ProblemDetail). */
@RestController
@RequestMapping("/api/v1/incidents")
class IncidentController {

    private final IncidentQueryService queries;

    IncidentController(IncidentQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/{id}")
    IncidentResponse get(@PathVariable UUID id) {
        return queries.findCard(id)
                .map(IncidentResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident " + id + " not found"));
    }

    /** Контракт API отделён от записи порта: поле в БД можно переименовать, не ломая клиентов. */
    record IncidentResponse(UUID id, String service, String environment, String category, Severity severity,
                            IncidentStatus status, Instant createdAt, Instant updatedAt, long eventCount,
                            LatestInvestigation latestInvestigation) {

        static IncidentResponse from(IncidentCard card) {
            LatestInvestigation latest = card.latestInvestigation() == null ? null : new LatestInvestigation(
                    card.latestInvestigation().id(), card.latestInvestigation().version(),
                    card.latestInvestigation().status());
            return new IncidentResponse(card.id(), card.service(), card.environment(), card.category(),
                    card.severity(), card.status(), card.createdAt(), card.updatedAt(), card.eventCount(), latest);
        }
    }

    record LatestInvestigation(UUID id, int version, InvestigationStatus status) {
    }
}
