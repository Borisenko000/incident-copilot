package dev.polina.copilot.adapter.in.rest;

import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.IncidentEvent.ErrorDetails;
import dev.polina.copilot.domain.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Тело POST /api/v1/incidents (README §5.2): человек вставляет текст ошибки без мониторинга.
 * eventId, occurredAt и source задаёт сервер; в событие попадает только введённое — метрики не выдумываются.
 */
record ManualIncidentRequest(
        @NotBlank @Size(max = 200) String title,
        String description,
        @NotBlank @Size(max = 200) String service,
        @NotBlank @Size(max = 200) String environment,
        @NotBlank String errorText,
        @Size(max = 200) String type,
        @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String severity,
        Map<String, Object> technology) {

    static final String SOURCE = "MANUAL";
    static final String DEFAULT_TYPE = "MANUAL";
    static final Severity DEFAULT_SEVERITY = Severity.MEDIUM;

    /** Полный текст остаётся в stackTrace; message — короткая строка для карточки, без дублирования длинного текста. */
    static final int MESSAGE_MAX_LENGTH = 500;

    IncidentEvent toDomain(Clock clock) {
        String eventType = type == null || type.isBlank() ? DEFAULT_TYPE : type.strip();
        Severity eventSeverity = severity == null ? DEFAULT_SEVERITY : Severity.valueOf(severity);
        // className не угадываем: класс исключения из свободного текста извлекать не будем (YAGNI)
        ErrorDetails error = new ErrorDetails(null, firstNonBlankLine(errorText), errorText);
        Map<String, Object> attributes = description == null || description.isBlank()
                ? Map.of()
                : Map.of("description", description.strip());

        return new IncidentEvent(UUID.randomUUID().toString(), SOURCE, clock.instant(), service.strip(),
                environment.strip(), eventType, eventSeverity, title.strip(), error, Map.of(), attributes, technology);
    }

    private static String firstNonBlankLine(String text) {
        return text.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .map(line -> line.length() > MESSAGE_MAX_LENGTH ? line.substring(0, MESSAGE_MAX_LENGTH) : line)
                .orElse(null);
    }
}
