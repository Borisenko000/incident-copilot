package dev.polina.copilot.application;

import dev.polina.copilot.domain.InvestigationStatus;
import java.util.UUID;

/** Итог приёма события: куда оно попало и было ли повтором. */
public record IngestResult(UUID incidentId, String eventId, InvestigationStatus investigationStatus,
                           boolean deduplicated) {
}
