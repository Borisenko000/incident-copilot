package dev.polina.copilot.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Нормализованное событие об ошибке (FR-01): в него превращается и REST-событие,
 * и (в фазе 2b) ручной ввод. Обязательны идентификация, время, сервис, тип и severity;
 * остальное может отсутствовать.
 */
public record IncidentEvent(
        String eventId,
        String source,
        Instant occurredAt,
        String service,
        String environment,
        String type,
        Severity severity,
        String summary,
        ErrorDetails error,
        Map<String, Object> correlation,
        Map<String, Object> attributes,
        Map<String, Object> technology) {

    public IncidentEvent {
        requireText(eventId, "eventId");
        requireText(source, "source");
        requireText(service, "service");
        requireText(environment, "environment");
        requireText(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(severity, "severity");
        correlation = immutableCopy(correlation);
        attributes = immutableCopy(attributes);
        technology = immutableCopy(technology);
    }

    /** Описание исключения; любое поле может быть null. */
    public record ErrorDetails(String className, String message, String stackTrace) {
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    // Map.copyOf не принимает null-значения, а в JSON они допустимы — поэтому LinkedHashMap
    private static Map<String, Object> immutableCopy(Map<String, Object> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
