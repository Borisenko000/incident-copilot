-- V3: индекс под запрос корреляции (фаза 2a):
-- WHERE fingerprint = ? AND status = 'OPEN' AND updated_at >= ? ORDER BY updated_at DESC
CREATE INDEX incidents_fingerprint_status_updated_idx ON ops.incidents (fingerprint, status, updated_at);
