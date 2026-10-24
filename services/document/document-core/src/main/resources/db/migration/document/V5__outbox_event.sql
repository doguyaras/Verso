-- The module's generic transactional outbox (reference 11.2, ADR-0039). The document module writes one row here in the
-- same transaction as the business change it reports (today: the audit record of an upload, a delete, a move or a
-- collection change), so a crash after the commit can no longer lose it. The relay of this module claims the rows
-- with FOR UPDATE SKIP LOCKED and a lease, delivers them, and deletes them; a row that cannot be delivered for a
-- reason that will not pass becomes DEAD and is deleted after the failure retention (verso.outbox.dead-retention).
-- The standard table of reference 11.2 (Debezium outbox router compatible).
--
-- Privacy (security-rules 4/5): the payload of an audit row is an AuditRecord, ids and fixed words only (the token
-- subject, Verso roles, the trace id, enum names); never a file name, a collection name or any document text.
-- svc_document gets SELECT, INSERT, UPDATE, DELETE through the schema's default privileges, as for every table here.
--
-- Rollout: a new, empty table; the old image never touches it. Rollback: DROP TABLE document.outbox_event once it is
-- empty (an undelivered row would be a lost audit record).

CREATE TABLE document.outbox_event (
    -- The event id; the receiver deduplicates by it (audit.event.event_id is unique).
    id              uuid        NOT NULL,
    kind            text        NOT NULL DEFAULT 'EVENT',
    aggregate_type  text        NOT NULL,
    aggregate_id    uuid        NOT NULL,
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    headers         jsonb       NOT NULL DEFAULT '{}',
    status          text        NOT NULL DEFAULT 'PENDING',
    priority        smallint    NOT NULL DEFAULT 0,
    dead_policy     text        NOT NULL DEFAULT 'DEAD_ON_PERMANENT',
    retry_count     integer     NOT NULL DEFAULT 0,
    next_retry_at   timestamptz NOT NULL DEFAULT now(),
    locked_until    timestamptz,
    claim_token     uuid,
    -- The exception type of the last failure (reference 11.2), never its message.
    last_error_code varchar(120),
    expires_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT ck_outbox_event_kind CHECK (kind IN ('EVENT', 'COMMAND', 'HTTP')),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHING', 'DEAD')),
    CONSTRAINT ck_outbox_event_dead_policy CHECK (dead_policy IN ('DEAD_ON_PERMANENT', 'NEVER_DEAD')),
    CONSTRAINT ck_outbox_event_type CHECK (event_type ~ '^[a-z][a-z0-9_.-]{0,99}$'),
    CONSTRAINT ck_outbox_event_aggregate_type CHECK (aggregate_type ~ '^[a-z][a-z0-9_.-]{0,99}$'),
    CONSTRAINT ck_outbox_event_claim CHECK ((status = 'PUBLISHING') = (claim_token IS NOT NULL))
);

-- The claim: due rows by status and retry time, highest priority and oldest first (reference 11.2).
CREATE INDEX idx_outbox_event_claim ON document.outbox_event (kind, status, next_retry_at, priority DESC, created_at);

-- High insert and delete churn on a small table: vacuum it early (reference 11.2 "Tablo sağlığı").
ALTER TABLE document.outbox_event SET (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_cost_delay = 1);
