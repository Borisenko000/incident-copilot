-- V1: полная схема P0 (README §7.2). После коммита не редактировать — только новые миграции.
-- Первичные ключи uuid генерирует приложение, поэтому без DEFAULT.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA ops;
CREATE SCHEMA knowledge;

-- ===================== ops: жизненный цикл инцидентов =====================

CREATE TABLE ops.incidents (
    id          uuid        PRIMARY KEY,
    service     text        NOT NULL,
    environment text        NOT NULL,
    category    text        NOT NULL,
    severity    text        NOT NULL,
    fingerprint text        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'CLOSED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX incidents_service_status_updated_idx ON ops.incidents (service, status, updated_at);

CREATE TABLE ops.incident_events (
    id          uuid        PRIMARY KEY,
    incident_id uuid        NOT NULL REFERENCES ops.incidents (id),
    source      text        NOT NULL,
    event_id    text        NOT NULL,
    occurred_at timestamptz NOT NULL,
    payload     jsonb       NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    -- идемпотентность приёма: одно и то же событие сохраняется один раз
    CONSTRAINT incident_events_source_event_uq UNIQUE (source, event_id)
);

CREATE TABLE ops.investigations (
    id             uuid        PRIMARY KEY,
    incident_id    uuid        NOT NULL REFERENCES ops.incidents (id),
    version        integer     NOT NULL CHECK (version > 0),
    status         text        NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'DIAGNOSED', 'INCONCLUSIVE', 'FAILED')),
    report         jsonb,
    state          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    model_name     text,
    prompt_version text,
    started_at     timestamptz,
    completed_at   timestamptz,
    CONSTRAINT investigations_incident_version_uq UNIQUE (incident_id, version)
);

CREATE TABLE ops.evidence (
    id               uuid        PRIMARY KEY,
    incident_id      uuid        NOT NULL REFERENCES ops.incidents (id),
    investigation_id uuid        REFERENCES ops.investigations (id),
    kind             text        NOT NULL,
    origin           text        NOT NULL,
    observed_at      timestamptz NOT NULL,
    content          jsonb       NOT NULL,
    digest           text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX evidence_incident_observed_idx ON ops.evidence (incident_id, observed_at);

-- Очередь заданий. FK nullable: не каждое задание связано с инцидентом (README §7.1).
CREATE TABLE ops.work_items (
    id               uuid        PRIMARY KEY,
    kind             text        NOT NULL,
    status           text        NOT NULL CHECK (status IN ('READY', 'LEASED', 'DONE', 'FAILED')),
    incident_id      uuid        REFERENCES ops.incidents (id),
    investigation_id uuid        REFERENCES ops.investigations (id),
    payload          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    attempts         integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    available_at     timestamptz NOT NULL DEFAULT now(),
    leased_until     timestamptz,
    lease_owner      text,
    dedupe_key       text,
    last_error       text
);

CREATE INDEX work_items_status_available_idx ON ops.work_items (status, available_at);
CREATE UNIQUE INDEX work_items_dedupe_key_uq ON ops.work_items (dedupe_key) WHERE dedupe_key IS NOT NULL;

-- ===================== knowledge: проверенная документация =====================

CREATE TABLE knowledge.sources (
    id              uuid        PRIMARY KEY,
    kind            text        NOT NULL,
    origin_uri      text        NOT NULL,
    product         text        NOT NULL,
    product_version text        NOT NULL,
    -- в поиск RAG попадают только APPROVED
    status          text        NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED')),
    trust_level     text,
    content_hash    text        NOT NULL,
    etag            text,
    last_modified   timestamptz,
    checked_at      timestamptz,
    metadata        jsonb       NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE knowledge.chunks (
    id              uuid    PRIMARY KEY,
    source_id       uuid    NOT NULL REFERENCES knowledge.sources (id) ON DELETE CASCADE,
    chunk_no        integer NOT NULL,
    content         text    NOT NULL,
    -- nullable: без embeddings поиск работает только по FTS (README §8.2)
    embedding       vector(1024),
    embedding_model text,
    content_hash    text    NOT NULL,
    CONSTRAINT chunks_source_chunk_no_uq UNIQUE (source_id, chunk_no)
);
