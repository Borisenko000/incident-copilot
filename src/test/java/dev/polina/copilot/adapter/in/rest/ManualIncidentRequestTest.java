package dev.polina.copilot.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.Severity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ManualIncidentRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    @DisplayName("поля формы раскладываются в IncidentEvent, серверные поля задаёт сервер")
    void mapsFieldsToDomainEvent() {
        ManualIncidentRequest request = new ManualIncidentRequest(
                "После деплоя HTTP 500", "ошибки на POST /orders", " order-service ", "staging",
                "\n\n  SQLTransientConnectionException: Connection is not available\n\tat com.zaxxer.Hikari",
                null, null, Map.of("springBoot", "4.1.x"));

        IncidentEvent event = request.toDomain(CLOCK);

        assertThat(UUID.fromString(event.eventId())).isNotNull();
        assertThat(event.source()).isEqualTo("MANUAL");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.service()).isEqualTo("order-service");
        assertThat(event.summary()).isEqualTo("После деплоя HTTP 500");
        assertThat(event.error().message()).isEqualTo("SQLTransientConnectionException: Connection is not available");
        assertThat(event.error().stackTrace()).isEqualTo(request.errorText());
        assertThat(event.attributes()).containsExactly(Map.entry("description", "ошибки на POST /orders"));
        assertThat(event.technology()).containsEntry("springBoot", "4.1.x");
    }

    @Test
    @DisplayName("без type и severity — значения по умолчанию MANUAL и MEDIUM")
    void appliesDefaults() {
        IncidentEvent event = minimal(null, null).toDomain(CLOCK);

        assertThat(event.type()).isEqualTo("MANUAL");
        assertThat(event.severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    @DisplayName("указанные type и severity используются как есть")
    void usesGivenTypeAndSeverity() {
        IncidentEvent event = minimal("DB_POOL_EXHAUSTED", "HIGH").toDomain(CLOCK);

        assertThat(event.type()).isEqualTo("DB_POOL_EXHAUSTED");
        assertThat(event.severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    @DisplayName("метрики не выдумываются: нет correlation, класса исключения и лишних атрибутов")
    void doesNotInventData() {
        IncidentEvent event = minimal(null, null).toDomain(CLOCK);

        assertThat(event.correlation()).isEmpty();
        assertThat(event.error().className()).isNull();
        assertThat(event.attributes()).isEmpty();
        assertThat(event.technology()).isEmpty();
    }

    @Test
    @DisplayName("пустой description не сохраняется, title обрезается по краям")
    void normalizesBlankDescriptionAndTitle() {
        ManualIncidentRequest request = new ManualIncidentRequest("  HTTP 500 \n", "   ", "order-service",
                "staging", "Connection is not available", null, null, null);

        IncidentEvent event = request.toDomain(CLOCK);

        assertThat(event.summary()).isEqualTo("HTTP 500");
        assertThat(event.attributes()).isEmpty();
    }

    @Test
    @DisplayName("message — не длиннее 500 символов, полный текст остаётся в stackTrace")
    void capsMessageLength() {
        String longLine = "x".repeat(2_000);
        ManualIncidentRequest request = new ManualIncidentRequest("HTTP 500", null, "order-service",
                "staging", longLine, null, null, null);

        IncidentEvent event = request.toDomain(CLOCK);

        assertThat(event.error().message()).hasSize(500);
        assertThat(event.error().stackTrace()).isEqualTo(longLine);
    }

    @Test
    @DisplayName("каждый вызов получает новый eventId")
    void eventIdIsUniquePerCall() {
        ManualIncidentRequest request = minimal(null, null);

        assertThat(request.toDomain(CLOCK).eventId()).isNotEqualTo(request.toDomain(CLOCK).eventId());
    }

    private static ManualIncidentRequest minimal(String type, String severity) {
        return new ManualIncidentRequest("HTTP 500", null, "order-service", "staging",
                "Connection is not available", type, severity, null);
    }
}
