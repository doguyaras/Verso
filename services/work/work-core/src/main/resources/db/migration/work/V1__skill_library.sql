-- Skill library (ADR-0057). Owner: the work module, schema "work", migrated as svc_work_migrate and used as svc_work
-- (reference 10.1). Ids are UUIDv7 from PostgreSQL 18 (reference 10.3). Other modules' ids (passages, documents,
-- collections) are plain uuid columns without a foreign key (AGENTS.md section 4): the document module owns them and
-- is reached only through document-api, with the reader's access, every time a run is shown.
--
-- Personal data: a run holds its owner's form inputs and the output written from them (VKED "beceri çalıştırmaları");
-- both are deleted with the run after the history retention (verso.work.retention-days) or by the owner, and by a KVKK
-- erasure through work-api WorkSubjects. No document text and no file name is stored here.

-- The installation's on/off switch per skill (built-in by id, customer-authored by id too). No row: enabled.
CREATE TABLE work.skill_setting (
    skill_id   text        NOT NULL,
    enabled    boolean     NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_skill_setting PRIMARY KEY (skill_id),
    CONSTRAINT ck_skill_setting_id CHECK (skill_id ~ '^[a-z][a-z0-9-]{2,63}$')
);

-- A customer-authored skill (the consulting team's company-specific ones). Runnable only while APPROVED; a new version
-- makes it a DRAFT again until a verso-admin approves it. Live ids and slash commands are unique; an archived skill
-- frees both. The author is in the audit log, not here.
CREATE TABLE work.custom_skill (
    id              uuid        NOT NULL DEFAULT uuidv7(),
    skill_id        text        NOT NULL,
    slash           text        NOT NULL,
    state           text        NOT NULL DEFAULT 'DRAFT',
    current_version integer     NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    approved_at     timestamptz,
    CONSTRAINT pk_custom_skill PRIMARY KEY (id),
    CONSTRAINT ck_custom_skill_id CHECK (skill_id ~ '^[a-z][a-z0-9-]{2,63}$'),
    CONSTRAINT ck_custom_skill_slash CHECK (slash ~ '^[a-z0-9-]{2,32}$'),
    CONSTRAINT ck_custom_skill_state CHECK (state IN ('DRAFT', 'APPROVED', 'ARCHIVED')),
    CONSTRAINT ck_custom_skill_version CHECK (current_version >= 1),
    CONSTRAINT ck_custom_skill_approved CHECK ((state = 'APPROVED') = (approved_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_custom_skill_live_id ON work.custom_skill (skill_id) WHERE state <> 'ARCHIVED';
CREATE UNIQUE INDEX uq_custom_skill_live_slash ON work.custom_skill (slash) WHERE state <> 'ARCHIVED';

-- Every saved version, unchanged after insert: what was approved can always be read back.
CREATE TABLE work.custom_skill_version (
    custom_skill_id uuid        NOT NULL,
    version         integer     NOT NULL,
    definition      jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_custom_skill_version PRIMARY KEY (custom_skill_id, version),
    CONSTRAINT fk_custom_skill_version_skill FOREIGN KEY (custom_skill_id) REFERENCES work.custom_skill (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_custom_skill_version_number CHECK (version >= 1),
    CONSTRAINT ck_custom_skill_version_object CHECK (jsonb_typeof(definition) = 'object')
);

REVOKE UPDATE ON work.custom_skill_version FROM svc_work;

-- One run. The definition it used is kept with it (a later version or an archived skill does not change how an old
-- run reads). lease_until is set while RUNNING: a run whose instance died is failed as INTERRUPTED once it passes.
CREATE TABLE work.skill_run (
    id              uuid        NOT NULL DEFAULT uuidv7(),
    owner_sub       text        NOT NULL,
    skill_id        text        NOT NULL,
    skill_version   text        NOT NULL,
    custom_skill_id uuid,
    definition      jsonb       NOT NULL,
    status          text        NOT NULL DEFAULT 'RUNNING',
    stage           text        NOT NULL DEFAULT 'QUEUED',
    step            smallint,
    step_count      smallint    NOT NULL,
    failure_code    text,
    inputs          jsonb       NOT NULL,
    output          jsonb,
    checks          jsonb,
    edited          boolean     NOT NULL DEFAULT false,
    rating          smallint,
    model_id        text,
    lease_until     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz,
    approved_at     timestamptz,
    CONSTRAINT pk_skill_run PRIMARY KEY (id),
    CONSTRAINT ck_skill_run_owner CHECK (length(owner_sub) BETWEEN 1 AND 255),
    CONSTRAINT ck_skill_run_skill CHECK (skill_id ~ '^[a-z][a-z0-9-]{2,63}$'),
    CONSTRAINT ck_skill_run_status CHECK (status IN ('RUNNING', 'DRAFT', 'APPROVED', 'FAILED')),
    CONSTRAINT ck_skill_run_stage CHECK (stage IN ('QUEUED', 'RETRIEVING', 'GENERATING', 'REPAIRING', 'CHECKING', 'FINISHED')),
    CONSTRAINT ck_skill_run_steps CHECK (step_count BETWEEN 1 AND 4 AND (step IS NULL OR step BETWEEN 1 AND step_count)),
    CONSTRAINT ck_skill_run_failure CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)
        AND (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{2,39}$')),
    CONSTRAINT ck_skill_run_lease CHECK ((status = 'RUNNING') = (lease_until IS NOT NULL)),
    CONSTRAINT ck_skill_run_output CHECK (status NOT IN ('DRAFT', 'APPROVED') OR output IS NOT NULL),
    CONSTRAINT ck_skill_run_approved CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL)),
    CONSTRAINT ck_skill_run_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    CONSTRAINT ck_skill_run_inputs CHECK (jsonb_typeof(inputs) = 'object')
);

-- One running run per user: the second start is a 409, decided by the index, not by a check-then-act (AGENTS.md 10).
CREATE UNIQUE INDEX uq_skill_run_running ON work.skill_run (owner_sub) WHERE status = 'RUNNING';
-- The owner's history, newest first.
CREATE INDEX idx_skill_run_owner ON work.skill_run (owner_sub, created_at DESC, id DESC);
-- Retention and the lost-run sweep.
CREATE INDEX idx_skill_run_created ON work.skill_run (created_at);
CREATE INDEX idx_skill_run_lease ON work.skill_run (lease_until) WHERE status = 'RUNNING';

-- The numbered sources of a run: what "evidence" numbers point at. A passage is kept by id only and re-read with the
-- viewer's access; an input part by its offsets in the run's own inputs. Written once when the run finishes.
CREATE TABLE work.skill_run_source (
    run_id        uuid     NOT NULL,
    number        smallint NOT NULL,
    kind          text     NOT NULL,
    passage_id    uuid,
    document_id   uuid,
    page          integer,
    unit          text,
    collection_id uuid,
    input_key     text,
    start_offset  integer,
    end_offset    integer,
    CONSTRAINT pk_skill_run_source PRIMARY KEY (run_id, number),
    CONSTRAINT fk_skill_run_source_run FOREIGN KEY (run_id) REFERENCES work.skill_run (id) ON DELETE CASCADE,
    CONSTRAINT ck_skill_run_source_number CHECK (number BETWEEN 1 AND 100),
    CONSTRAINT ck_skill_run_source_kind CHECK (kind IN ('PASSAGE', 'INPUT')),
    CONSTRAINT ck_skill_run_source_passage CHECK ((kind = 'PASSAGE') = (passage_id IS NOT NULL AND document_id IS NOT NULL)),
    CONSTRAINT ck_skill_run_source_input CHECK ((kind = 'INPUT') = (input_key IS NOT NULL AND start_offset IS NOT NULL
        AND end_offset IS NOT NULL)),
    CONSTRAINT ck_skill_run_source_unit CHECK (unit IS NULL OR unit IN ('PAGE', 'SECTION'))
);

REVOKE UPDATE ON work.skill_run_source FROM svc_work;

-- Transactional outbox (ADR-0039, reference 11.2): the audit record of a run or an admin change commits with it, and
-- the relay copies it to the audit log. The payload is an AuditRecord, ids and fixed words only.
CREATE TABLE work.outbox_event (
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

CREATE INDEX idx_outbox_event_claim ON work.outbox_event (kind, status, next_retry_at, priority DESC, created_at);

ALTER TABLE work.outbox_event SET (autovacuum_vacuum_scale_factor = 0.01, autovacuum_vacuum_cost_delay = 1);
