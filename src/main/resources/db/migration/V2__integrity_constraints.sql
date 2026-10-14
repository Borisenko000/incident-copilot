-- V2: ограничения целостности и индексы по итогам code review фазы 1.

-- Задание в аренде обязано знать срок аренды и владельца,
-- иначе восстановление (leased_until < now()) его никогда не найдёт.
ALTER TABLE ops.work_items
    ADD CONSTRAINT work_items_lease_filled_chk
        CHECK (status <> 'LEASED' OR (leased_until IS NOT NULL AND lease_owner IS NOT NULL));

-- Расследование и инцидент в evidence/work_items должны совпадать:
-- нельзя сослаться на расследование чужого инцидента.
-- Составной FK проверяется, только когда обе колонки не NULL.
ALTER TABLE ops.investigations
    ADD CONSTRAINT investigations_id_incident_uq UNIQUE (id, incident_id);

ALTER TABLE ops.evidence
    ADD CONSTRAINT evidence_investigation_same_incident_fk
        FOREIGN KEY (investigation_id, incident_id) REFERENCES ops.investigations (id, incident_id);

ALTER TABLE ops.work_items
    ADD CONSTRAINT work_items_investigation_same_incident_fk
        FOREIGN KEY (investigation_id, incident_id) REFERENCES ops.investigations (id, incident_id);

-- Индексы под реальные запросы: события инцидента и gate EVIDENCE.
CREATE INDEX incident_events_incident_idx ON ops.incident_events (incident_id);
CREATE INDEX evidence_investigation_idx ON ops.evidence (investigation_id);
