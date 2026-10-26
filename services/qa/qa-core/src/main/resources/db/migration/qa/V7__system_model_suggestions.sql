-- Suggested relations of the system model (ADR-0048 phase 4, ADR-0054). Owner: the qa module, schema "qa". The light
-- system model itself is extracted deterministically by the document module; this table holds only what the optional
-- local-model pass proposes on top of it, for a verso-admin's review. A suggestion is never trusted: it is DRAFT until
-- an admin accepts or rejects it, and nothing reads an accepted one into retrieval, lookup or the map's own relations
-- (they stay deterministic); the map shows accepted ones as "reviewed suggestions". The pass is off by default.
--
-- A suggestion relates two entities the deterministic pass already found in the same document (by type and folded
-- key, never free text) and is bound to the chunk it was read from (id and SHA-256). Document and chunk ids are plain
-- uuid columns without a foreign key: the document schema is never reached from here (AGENTS.md section 4); a deleted or
-- replaced document deletes its suggestions through the document outbox (DocumentChangeListener).
--
-- Privacy (ADR-0054): entity keys are codes, abbreviations, system, screen and process names and job titles; the
-- deterministic pass never makes a person an entity, so no suggestion can name one. Account ids of the admins who ran
-- and reviewed (accountability, like cards and skills). Nothing here is logged.
--
-- Rollout: a new, empty table; the old image never reads it. Rollback: DROP TABLE qa.system_model_suggestion.

CREATE TABLE qa.system_model_suggestion (
    id            uuid        NOT NULL DEFAULT uuidv7(),
    document_id   uuid        NOT NULL,
    chunk_id      uuid        NOT NULL,
    chunk_sha256  text        NOT NULL,
    relation      text        NOT NULL,
    source_type   text        NOT NULL,
    source_key    text        NOT NULL,
    target_type   text        NOT NULL,
    target_key    text        NOT NULL,
    state         text        NOT NULL DEFAULT 'DRAFT',
    -- The token sub of the admin who ran the pass, and of the one who reviewed (ADR-0005).
    created_by    text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    reviewed_by   text,
    reviewed_at   timestamptz,
    CONSTRAINT pk_system_model_suggestion PRIMARY KEY (id),
    CONSTRAINT uq_system_model_suggestion UNIQUE (document_id, relation, source_type, source_key, target_type, target_key),
    CONSTRAINT ck_system_model_suggestion_relation CHECK (relation IN ('STEP_OF', 'NEXT_STEP', 'CODE_OF', 'PART_OF', 'OWNED_BY')),
    CONSTRAINT ck_system_model_suggestion_types CHECK (
        source_type IN ('SYSTEM', 'SCREEN', 'CODE', 'ROLE', 'PROCESS', 'STEP', 'TERM')
        AND target_type IN ('SYSTEM', 'SCREEN', 'CODE', 'ROLE', 'PROCESS', 'STEP', 'TERM')),
    CONSTRAINT ck_system_model_suggestion_keys CHECK
        (char_length(source_key) BETWEEN 1 AND 200 AND char_length(target_key) BETWEEN 1 AND 200),
    CONSTRAINT ck_system_model_suggestion_sha256 CHECK (chunk_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_system_model_suggestion_state CHECK (state IN ('DRAFT', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_system_model_suggestion_review CHECK
        ((state = 'DRAFT') = (reviewed_by IS NULL AND reviewed_at IS NULL)),
    CONSTRAINT ck_system_model_suggestion_creator CHECK (char_length(created_by) BETWEEN 1 AND 256)
);
-- The review list: one state, newest first.
CREATE INDEX idx_system_model_suggestion_state ON qa.system_model_suggestion (state, created_at DESC, id DESC);
-- A document change deletes its suggestions.
CREATE INDEX idx_system_model_suggestion_document ON qa.system_model_suggestion (document_id);
