package dev.polina.copilot.adapter.out.persistence;

import dev.polina.copilot.domain.Fingerprint;
import dev.polina.copilot.domain.IncidentEvent;
import dev.polina.copilot.domain.IncidentStatus;
import dev.polina.copilot.domain.InvestigationStatus;
import dev.polina.copilot.domain.Severity;
import dev.polina.copilot.port.IncidentRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL-реализация {@link IncidentRepository} на JdbcClient: явный SQL, без JPA.
 * Время передаётся как OffsetDateTime в UTC — драйвер PostgreSQL не принимает Instant напрямую.
 */
@Repository
public class JdbcIncidentRepository implements IncidentRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcIncidentRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void lockFingerprint(Fingerprint fingerprint) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param(fingerprint.value())
                .query((rs, rowNum) -> 1)
                .single();
    }

    @Override
    public Optional<UUID> findIncidentIdByEvent(String source, String eventId) {
        return jdbc.sql("SELECT incident_id FROM ops.incident_events WHERE source = ? AND event_id = ?")
                .params(source, eventId)
                .query(UUID.class)
                .optional();
    }

    @Override
    public Optional<OpenIncident> findOpenIncident(Fingerprint fingerprint, Instant activeSince) {
        return jdbc.sql("""
                        SELECT id, severity FROM ops.incidents
                        WHERE fingerprint = ? AND status = 'OPEN' AND updated_at >= ?
                        ORDER BY updated_at DESC
                        LIMIT 1
                        """)
                .params(fingerprint.value(), utc(activeSince))
                .query((rs, rowNum) -> new OpenIncident(
                        rs.getObject("id", UUID.class), Severity.valueOf(rs.getString("severity"))))
                .optional();
    }

    @Override
    public InvestigationStatus latestInvestigationStatus(UUID incidentId) {
        String status = jdbc.sql("""
                        SELECT status FROM ops.investigations
                        WHERE incident_id = ?
                        ORDER BY version DESC
                        LIMIT 1
                        """)
                .param(incidentId)
                .query(String.class)
                .single();
        return InvestigationStatus.valueOf(status);
    }

    @Override
    public void insertIncident(NewIncident i) {
        jdbc.sql("""
                        INSERT INTO ops.incidents
                            (id, service, environment, category, severity, fingerprint, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(i.id(), i.service(), i.environment(), i.category(), i.severity().name(),
                        i.fingerprint().value(), i.status().name(), utc(i.createdAt()), utc(i.createdAt()))
                .update();
    }

    @Override
    public void updateIncidentActivity(UUID incidentId, Severity severity, Instant updatedAt) {
        jdbc.sql("UPDATE ops.incidents SET severity = ?, updated_at = ? WHERE id = ?")
                .params(severity.name(), utc(updatedAt), incidentId)
                .update();
    }

    @Override
    public void insertEvent(UUID id, UUID incidentId, IncidentEvent event, Instant receivedAt) {
        jdbc.sql("""
                        INSERT INTO ops.incident_events (id, incident_id, source, event_id, occurred_at, payload, received_at)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                        """)
                .params(id, incidentId, event.source(), event.eventId(), utc(event.occurredAt()),
                        json.writeValueAsString(event), utc(receivedAt))
                .update();
    }

    @Override
    public void insertInvestigation(UUID id, UUID incidentId, int version, InvestigationStatus status) {
        jdbc.sql("INSERT INTO ops.investigations (id, incident_id, version, status) VALUES (?, ?, ?, ?)")
                .params(id, incidentId, version, status.name())
                .update();
    }

    @Override
    public void insertWorkItem(UUID id, UUID incidentId, UUID investigationId, String kind, String dedupeKey) {
        jdbc.sql("""
                        INSERT INTO ops.work_items (id, kind, status, incident_id, investigation_id, dedupe_key)
                        VALUES (?, ?, 'READY', ?, ?, ?)
                        """)
                .params(id, kind, incidentId, investigationId, dedupeKey)
                .update();
    }

    @Override
    public Optional<IncidentCard> findCard(UUID incidentId) {
        return jdbc.sql("""
                        SELECT i.id, i.service, i.environment, i.category, i.severity, i.status,
                               i.created_at, i.updated_at,
                               (SELECT count(*) FROM ops.incident_events e WHERE e.incident_id = i.id) AS event_count,
                               inv.id AS inv_id, inv.version AS inv_version, inv.status AS inv_status
                        FROM ops.incidents i
                        LEFT JOIN LATERAL (
                            SELECT id, version, status FROM ops.investigations
                            WHERE incident_id = i.id
                            ORDER BY version DESC
                            LIMIT 1
                        ) inv ON true
                        WHERE i.id = ?
                        """)
                .param(incidentId)
                .query(JdbcIncidentRepository::mapCard)
                .optional();
    }

    private static IncidentCard mapCard(ResultSet rs, int rowNum) throws SQLException {
        UUID investigationId = rs.getObject("inv_id", UUID.class);
        LatestInvestigation latest = investigationId == null ? null : new LatestInvestigation(
                investigationId, rs.getInt("inv_version"), InvestigationStatus.valueOf(rs.getString("inv_status")));
        return new IncidentCard(
                rs.getObject("id", UUID.class),
                rs.getString("service"),
                rs.getString("environment"),
                rs.getString("category"),
                Severity.valueOf(rs.getString("severity")),
                IncidentStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getLong("event_count"),
                latest);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
