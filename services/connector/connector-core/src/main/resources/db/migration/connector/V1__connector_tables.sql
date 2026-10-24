-- File-share connectors (ADR-0038). Owner: the connector module, schema "connector", migrated as
-- svc_connector_migrate and used as svc_connector (reference 10.1). Ids are UUIDv7 from PostgreSQL 18 (reference 10.3).
-- Other modules' ids (the target collection, the synced documents) are plain uuid columns without a foreign key
-- (AGENTS.md section 4): the document module owns them and is reached only through document-api.
--
-- No secret is stored: credential_ref is the NAME of a secret file under /run/secrets (security-rules 1). File paths
-- live only in connector_item, for the admin's failure list; they are never logged and never audited.

CREATE TABLE connector.connector (
    id                      uuid        NOT NULL DEFAULT uuidv7(),
    name                    text        NOT NULL,
    type                    text        NOT NULL DEFAULT 'SMB',
    host                    text        NOT NULL,
    port                    integer     NOT NULL DEFAULT 445,
    share                   text        NOT NULL,
    -- Normalised: "" for the whole share, otherwise "a/b" (no ".", "..", ":" or empty segment).
    path_prefix             text        NOT NULL DEFAULT '',
    domain                  text,
    username                text        NOT NULL,
    credential_ref          text        NOT NULL,
    collection_id           uuid        NOT NULL,
    include_globs           text[]      NOT NULL DEFAULT '{}',
    exclude_globs           text[]      NOT NULL DEFAULT '{}',
    max_file_bytes          bigint      NOT NULL,
    schedule_minutes        integer     NOT NULL,
    enabled                 boolean     NOT NULL DEFAULT true,
    -- Deleted by an admin: the worker removes its documents, then the row.
    deleting                boolean     NOT NULL DEFAULT false,
    -- One run may exceed the deletion safety (ADR-0038); consumed by the run that used it.
    mass_deletion_confirmed boolean     NOT NULL DEFAULT false,
    created_by              text        NOT NULL,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    -- Schedule and distributed lock (AGENTS.md section 10): FOR UPDATE SKIP LOCKED + lease + claim token.
    next_run_at             timestamptz NOT NULL,
    sync_requested_at       timestamptz,
    locked_until            timestamptz,
    claim_token             uuid,
    -- Outcome of the runs; last_success_at drives health and the ConnectorSyncStale alert.
    consecutive_failures    integer     NOT NULL DEFAULT 0,
    last_success_at         timestamptz,
    last_failure_at         timestamptz,
    last_error_code         text,
    CONSTRAINT pk_connector PRIMARY KEY (id),
    CONSTRAINT ck_connector_type CHECK (type = 'SMB'),
    CONSTRAINT ck_connector_name CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_connector_host CHECK (host ~ '^[A-Za-z0-9.:-]{1,253}$'),
    CONSTRAINT ck_connector_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_connector_share CHECK (share ~ '^[A-Za-z0-9_.$ -]{1,80}$'),
    CONSTRAINT ck_connector_path_prefix CHECK (char_length(path_prefix) <= 1024 AND path_prefix !~ '(^|/)\.\.?(/|$)'
        AND position(':' IN path_prefix) = 0 AND position(E'\\' IN path_prefix) = 0),
    CONSTRAINT ck_connector_domain CHECK (domain IS NULL OR domain ~ '^[A-Za-z0-9.-]{0,253}$'),
    CONSTRAINT ck_connector_username CHECK (username ~ '^[A-Za-z0-9._@$ -]{1,104}$'),
    CONSTRAINT ck_connector_credential_ref CHECK (credential_ref ~ '^SECRET_CONNECTOR_[A-Z0-9_]{1,60}$'),
    CONSTRAINT ck_connector_globs CHECK (cardinality(include_globs) <= 20 AND cardinality(exclude_globs) <= 20),
    CONSTRAINT ck_connector_max_file_bytes CHECK (max_file_bytes > 0),
    CONSTRAINT ck_connector_schedule CHECK (schedule_minutes BETWEEN 5 AND 10080),
    CONSTRAINT ck_connector_error_code CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z0-9_]{1,40}$')
);

CREATE UNIQUE INDEX uq_connector_name ON connector.connector (lower(name));
-- The worker's claim: due connectors, deletions first.
CREATE INDEX idx_connector_due ON connector.connector (next_run_at) WHERE enabled OR deleting;

-- One row per file the connector tracks. path is relative to the prefix, "/"-separated. The fingerprint (file id, size,
-- modification time) decides "unchanged" without a download; content_sha256 decides "changed" after one.
CREATE TABLE connector.connector_item (
    connector_id     uuid        NOT NULL,
    path             text        NOT NULL,
    file_id          bigint      NOT NULL,
    size_bytes       bigint      NOT NULL,
    modified_at      timestamptz NOT NULL,
    content_sha256   text,
    document_id      uuid,
    state            text        NOT NULL,
    failure_code     text,
    attempts         integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT pk_connector_item PRIMARY KEY (connector_id, path),
    CONSTRAINT fk_connector_item_connector FOREIGN KEY (connector_id)
        REFERENCES connector.connector (id) ON DELETE CASCADE,
    CONSTRAINT ck_connector_item_path CHECK (char_length(path) BETWEEN 1 AND 1024),
    CONSTRAINT ck_connector_item_state CHECK (state IN ('SYNCED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_connector_item_sha CHECK (content_sha256 IS NULL OR content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_connector_item_failure CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z0-9_]{1,40}$')
);

-- The admin's failure list and the state counts.
CREATE INDEX idx_connector_item_state ON connector.connector_item (connector_id, state, path);

-- Sync runs: counts and a fixed error code only, never a file name.
CREATE TABLE connector.sync_run (
    id                uuid        NOT NULL DEFAULT uuidv7(),
    connector_id      uuid        NOT NULL,
    started_at        timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    status            text        NOT NULL,
    error_code        text,
    listing_complete  boolean     NOT NULL DEFAULT false,
    listed            integer     NOT NULL DEFAULT 0,
    added             integer     NOT NULL DEFAULT 0,
    updated           integer     NOT NULL DEFAULT 0,
    deleted           integer     NOT NULL DEFAULT 0,
    unchanged         integer     NOT NULL DEFAULT 0,
    failed            integer     NOT NULL DEFAULT 0,
    skipped           integer     NOT NULL DEFAULT 0,
    deferred          integer     NOT NULL DEFAULT 0,
    CONSTRAINT pk_sync_run PRIMARY KEY (id),
    CONSTRAINT fk_sync_run_connector FOREIGN KEY (connector_id) REFERENCES connector.connector (id) ON DELETE CASCADE,
    CONSTRAINT ck_sync_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'ABANDONED')),
    CONSTRAINT ck_sync_run_error_code CHECK (error_code IS NULL OR error_code ~ '^[A-Z0-9_]{1,40}$')
);

CREATE INDEX idx_sync_run_connector ON connector.sync_run (connector_id, started_at DESC, id DESC);

-- The module's transactional outbox (reference 11.2, ADR-0039), the same standard table as document and qa: the audit
-- record of an admin change or a sync run commits with it, and the relay copies it to the audit log. The payload is an
-- AuditRecord, ids and fixed words only.
CREATE TABLE connector.outbox_event (
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
CREATE INDEX idx_outbox_event_claim ON connector.outbox_event (kind, status, next_retry_at, priority DESC, created_at);

-- High insert and delete churn on a small table: vacuum it early (reference 11.2 "Tablo sağlığı").
ALTER TABLE connector.outbox_event SET (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_cost_delay = 1);
