package dev.polina.copilot.application;

import dev.polina.copilot.port.IncidentRepository;
import dev.polina.copilot.port.IncidentRepository.IncidentCard;
import java.util.Optional;
import java.util.UUID;

/** Use case чтения карточки инцидента для GET /api/v1/incidents/{id}. */
public final class IncidentQueryService {

    private final IncidentRepository repository;

    public IncidentQueryService(IncidentRepository repository) {
        this.repository = repository;
    }

    public Optional<IncidentCard> findCard(UUID incidentId) {
        return repository.findCard(incidentId);
    }
}
