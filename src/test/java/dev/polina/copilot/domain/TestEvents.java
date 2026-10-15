package dev.polina.copilot.domain;

import dev.polina.copilot.domain.IncidentEvent.ErrorDetails;
import java.time.Instant;
import java.util.Map;

/** Билдер тестовых событий: по умолчанию — валидное событие DB_POOL_EXHAUSTED, в тесте меняется только нужное. */
public final class TestEvents {

    private String eventId = "evt-1";
    private String source = "simulator";
    private Instant occurredAt = Instant.parse("2026-10-15T10:00:00Z");
    private String service = "order-service";
    private String environment = "staging";
    private String type = "DB_POOL_EXHAUSTED";
    private Severity severity = Severity.HIGH;
    private String summary = "Connection is not available";
    private ErrorDetails error = new ErrorDetails(
            "java.sql.SQLTransientConnectionException", "HikariPool-1 - Connection is not available", null);
    private Map<String, Object> correlation = Map.of();
    private Map<String, Object> attributes = Map.of();
    private Map<String, Object> technology = Map.of();

    private TestEvents() {
    }

    public static TestEvents event() {
        return new TestEvents();
    }

    public TestEvents eventId(String value) { this.eventId = value; return this; }
    public TestEvents source(String value) { this.source = value; return this; }
    public TestEvents occurredAt(Instant value) { this.occurredAt = value; return this; }
    public TestEvents service(String value) { this.service = value; return this; }
    public TestEvents environment(String value) { this.environment = value; return this; }
    public TestEvents type(String value) { this.type = value; return this; }
    public TestEvents severity(Severity value) { this.severity = value; return this; }
    public TestEvents summary(String value) { this.summary = value; return this; }
    public TestEvents error(ErrorDetails value) { this.error = value; return this; }
    public TestEvents correlation(Map<String, Object> value) { this.correlation = value; return this; }
    public TestEvents attributes(Map<String, Object> value) { this.attributes = value; return this; }
    public TestEvents technology(Map<String, Object> value) { this.technology = value; return this; }

    public IncidentEvent build() {
        return new IncidentEvent(eventId, source, occurredAt, service, environment, type, severity,
                summary, error, correlation, attributes, technology);
    }
}
