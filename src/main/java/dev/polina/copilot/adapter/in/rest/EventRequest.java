package dev.polina.copilot.adapter.in.rest;

import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.IncidentEvent.ErrorDetails;
import dev.polina.copilot.domain.Severity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

/**
 * Тело POST /api/v1/events (README §5.1). Правила полей нарушены → 422.
 * severity — строка, а не enum: неизвестное значение должно дать 422, а не ошибку разбора JSON (400).
 */
record EventRequest(
        @NotBlank @Size(max = 200) String eventId,
        @NotBlank @Size(max = 200) String source,
        @NotNull Instant occurredAt,
        @NotBlank @Size(max = 200) String service,
        @NotBlank @Size(max = 200) String environment,
        @NotBlank @Size(max = 200) String type,
        @NotNull @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String severity,
        String summary,
        @Valid ErrorRequest error,
        Map<String, Object> correlation,
        Map<String, Object> attributes,
        Map<String, Object> technology) {

    record ErrorRequest(String className, String message, String stackTrace) {
    }

    IncidentEvent toDomain() {
        ErrorDetails details = error == null ? null
                : new ErrorDetails(error.className(), error.message(), error.stackTrace());
        return new IncidentEvent(eventId.strip(), source.strip(), occurredAt, service.strip(), environment.strip(),
                type.strip(), Severity.valueOf(severity), summary, details, correlation, attributes, technology);
    }
}
