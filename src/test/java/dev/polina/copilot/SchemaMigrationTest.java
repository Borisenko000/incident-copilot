package dev.polina.copilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Проверяет, что миграция V1 создала схему БД с нужными ограничениями. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional // каждый тест откатывается — тесты не мешают друг другу
class SchemaMigrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("в ops ровно 5 таблиц, в knowledge — 2")
    void createsAllTables() {
        assertThat(tablesIn("ops"))
                .containsExactlyInAnyOrder("incidents", "incident_events", "investigations", "evidence", "work_items");
        assertThat(tablesIn("knowledge")).containsExactlyInAnyOrder("sources", "chunks");
    }

    @Test
    @DisplayName("расширение pgvector установлено")
    void installsVectorExtension() {
        Integer count = jdbc.sql("SELECT count(*) FROM pg_extension WHERE extname = 'vector'")
                .query(Integer.class).single();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("повторное событие с тем же (source, event_id) отклоняется")
    void rejectsDuplicateEvent() {
        UUID incidentId = insertIncident("OPEN");
        insertEvent(incidentId, "payments", "evt-1");

        assertThatThrownBy(() -> insertEvent(incidentId, "payments", "evt-1"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("недопустимый статус инцидента отклоняется CHECK")
    void rejectsUnknownIncidentStatus() {
        assertThatThrownBy(() -> insertIncident("BROKEN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("вектор длины 1024 сохраняется")
    void acceptsVectorOf1024() {
        UUID sourceId = insertSource();
        insertChunk(sourceId, 1, vectorOf(1024));

        Integer dims = jdbc.sql("SELECT vector_dims(embedding) FROM knowledge.chunks WHERE source_id = ?")
                .param(sourceId).query(Integer.class).single();
        assertThat(dims).isEqualTo(1024);
    }

    @Test
    @DisplayName("вектор другой длины отклоняется")
    void rejectsVectorOfOtherLength() {
        UUID sourceId = insertSource();

        assertThatThrownBy(() -> insertChunk(sourceId, 1, vectorOf(768)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("1024");
    }

    @Test
    @DisplayName("задание LEASED без leased_until и lease_owner отклоняется")
    void rejectsLeasedWorkItemWithoutLease() {
        assertThatThrownBy(() -> insertWorkItem("LEASED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("задание LEASED с заполненным lease сохраняется")
    void acceptsLeasedWorkItemWithLease() {
        insertWorkItem("LEASED", "now() + interval '5 minutes'", "'worker-1'");
    }

    @Test
    @DisplayName("evidence не может ссылаться на расследование другого инцидента")
    void rejectsEvidenceOfForeignInvestigation() {
        UUID incidentA = insertIncident("OPEN");
        UUID incidentB = insertIncident("OPEN");
        UUID investigationOfA = insertInvestigation(incidentA);

        assertThatThrownBy(() -> insertEvidence(incidentB, investigationOfA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private java.util.List<String> tablesIn(String schema) {
        return jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = ? AND table_type = 'BASE TABLE'
                        """)
                .param(schema).query(String.class).list();
    }

    private UUID insertIncident(String status) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ops.incidents (id, service, environment, category, severity, fingerprint, status)
                        VALUES (?, 'payments', 'local', 'DB_POOL_EXHAUSTED', 'HIGH', 'fp-1', ?)
                        """)
                .params(id, status).update();
        return id;
    }

    private void insertEvent(UUID incidentId, String source, String eventId) {
        jdbc.sql("""
                        INSERT INTO ops.incident_events (id, incident_id, source, event_id, occurred_at, payload)
                        VALUES (?, ?, ?, ?, now(), '{}'::jsonb)
                        """)
                .params(UUID.randomUUID(), incidentId, source, eventId).update();
    }

    private void insertWorkItem(String status, String leasedUntilSql, String leaseOwnerSql) {
        jdbc.sql("INSERT INTO ops.work_items (id, kind, status, leased_until, lease_owner) VALUES (?, 'INVESTIGATE', ?, "
                        + (leasedUntilSql == null ? "NULL" : leasedUntilSql) + ", "
                        + (leaseOwnerSql == null ? "NULL" : leaseOwnerSql) + ")")
                .params(UUID.randomUUID(), status).update();
    }

    private UUID insertInvestigation(UUID incidentId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO ops.investigations (id, incident_id, version, status) VALUES (?, ?, 1, 'QUEUED')")
                .params(id, incidentId).update();
        return id;
    }

    private void insertEvidence(UUID incidentId, UUID investigationId) {
        jdbc.sql("""
                        INSERT INTO ops.evidence (id, incident_id, investigation_id, kind, origin, observed_at, content, digest)
                        VALUES (?, ?, ?, 'LOG', 'simulator', now(), '{}'::jsonb, 'd-1')
                        """)
                .params(UUID.randomUUID(), incidentId, investigationId).update();
    }

    private UUID insertSource() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO knowledge.sources (id, kind, origin_uri, product, product_version, status, content_hash)
                        VALUES (?, 'RUNBOOK', 'knowledge/hikari.md', 'HikariCP', '6.3', 'APPROVED', 'hash-1')
                        """)
                .param(id).update();
        return id;
    }

    private void insertChunk(UUID sourceId, int chunkNo, String vector) {
        jdbc.sql("""
                        INSERT INTO knowledge.chunks (id, source_id, chunk_no, content, embedding, content_hash)
                        VALUES (?, ?, ?, 'text', ?::vector, 'hash-1')
                        """)
                .params(UUID.randomUUID(), sourceId, chunkNo, vector).update();
    }

    /** Строка вида "[0.1,0.1,...]" — так pgvector принимает вектор. */
    private static String vectorOf(int dims) {
        return "[" + String.join(",", Collections.nCopies(dims, "0.1")) + "]";
    }
}
