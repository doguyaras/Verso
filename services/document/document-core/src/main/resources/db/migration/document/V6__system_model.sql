-- The light, typed system model of the learning knowledge layer (ADR-0048 phase 4, ADR-0054): per document, the
-- systems, screens, codes, roles, processes, steps and terms a deterministic pass found in its chunks, their relations,
-- and where each is mentioned. Postgres tables, no graph database (ADR-0001). Owner: the document module.
--
-- Bound to the source on purpose: every row hangs off its document (ON DELETE CASCADE: a deleted document takes its
-- model with it, KVKK erasure), every mention and relation off the chunk it was read from (ON DELETE CASCADE: a
-- re-chunked document loses the old evidence in the same statement), and each mention keeps the chunk's SHA-256. The
-- model of one document is replaced as a whole in one transaction; there is no global rebuild.
--
-- Privacy (ADR-0054, security-rules 4): no person entity exists; an owner is a job title or a department, reduced from
-- the phrase by the extractor. Labels and values are document text: never logged, shown only through reader-filtered
-- queries (ADR-0028).
--
-- svc_document gets SELECT, INSERT, DELETE through the schema's default privileges; UPDATE is revoked below (rows are
-- replaced, never changed). Rollout: new, empty tables; the old image never reads them; the backfill job fills them
-- for existing documents. Rollback: DROP the four tables.

CREATE TABLE document.system_entity (
    id           uuid    NOT NULL DEFAULT uuidv7(),
    document_id  uuid    NOT NULL,
    type         text    NOT NULL,
    -- Folded lookup key (Turkish folding, ADR-0047): "IK-3" for "İK-3", "FEFO", "depo sorumlusu"; a step's key is its
    -- process key and "#<n>".
    key          text    NOT NULL,
    -- As one chunk wrote it; document text.
    label        text    NOT NULL,
    -- The number of a STEP; NULL for every other type.
    ordinal      integer,
    CONSTRAINT pk_system_entity PRIMARY KEY (id),
    CONSTRAINT fk_system_entity_document FOREIGN KEY (document_id) REFERENCES document.document (id) ON DELETE CASCADE,
    CONSTRAINT uq_system_entity_document_type_key UNIQUE (document_id, type, key),
    CONSTRAINT ck_system_entity_type CHECK (type IN ('SYSTEM', 'SCREEN', 'CODE', 'ROLE', 'PROCESS', 'STEP', 'TERM')),
    CONSTRAINT ck_system_entity_key CHECK (char_length(key) BETWEEN 1 AND 200),
    CONSTRAINT ck_system_entity_label CHECK (char_length(label) BETWEEN 1 AND 200),
    CONSTRAINT ck_system_entity_ordinal CHECK ((type = 'STEP') = (ordinal IS NOT NULL) AND (ordinal IS NULL OR ordinal >= 1))
);
-- Lookup, enrichment and the system map: one key across all documents (then the reader filter per document).
CREATE INDEX idx_system_entity_type_key ON document.system_entity (type, key);
-- Searching the map by the start of a key.
CREATE INDEX idx_system_entity_key_prefix ON document.system_entity (key text_pattern_ops);

-- Relations between entities of the same document. DEFINES points to a value (an expansion, a description), every
-- other type to a target entity. chunk_id is the evidence; the relation goes with it.
CREATE TABLE document.system_relation (
    id            uuid    NOT NULL DEFAULT uuidv7(),
    document_id   uuid    NOT NULL,
    type          text    NOT NULL,
    source_id     uuid    NOT NULL,
    target_id     uuid,
    value         text,
    chunk_id      uuid    NOT NULL,
    chunk_sha256  text    NOT NULL,
    CONSTRAINT pk_system_relation PRIMARY KEY (id),
    CONSTRAINT fk_system_relation_document FOREIGN KEY (document_id) REFERENCES document.document (id) ON DELETE CASCADE,
    CONSTRAINT fk_system_relation_source FOREIGN KEY (source_id) REFERENCES document.system_entity (id) ON DELETE CASCADE,
    CONSTRAINT fk_system_relation_target FOREIGN KEY (target_id) REFERENCES document.system_entity (id) ON DELETE CASCADE,
    CONSTRAINT fk_system_relation_chunk FOREIGN KEY (chunk_id) REFERENCES document.document_chunk (id) ON DELETE CASCADE,
    CONSTRAINT ck_system_relation_type CHECK (type IN ('STEP_OF', 'NEXT_STEP', 'CODE_OF', 'PART_OF', 'OWNED_BY', 'DEFINES')),
    CONSTRAINT ck_system_relation_shape CHECK
        ((type = 'DEFINES') = (target_id IS NULL AND value IS NOT NULL AND char_length(value) BETWEEN 1 AND 300)),
    CONSTRAINT ck_system_relation_sha256 CHECK (chunk_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_system_relation_source ON document.system_relation (source_id, type);
CREATE INDEX idx_system_relation_target ON document.system_relation (target_id, type) WHERE target_id IS NOT NULL;
-- The chunk foreign key: a re-chunk deletes chunks by document, each delete probes this index.
CREATE INDEX idx_system_relation_chunk ON document.system_relation (chunk_id);
CREATE INDEX idx_system_relation_document ON document.system_relation (document_id);

-- MENTIONED_IN: the entity occurs in the chunk; defining when the chunk defines it (expansion, description, steps).
CREATE TABLE document.system_mention (
    entity_id     uuid    NOT NULL,
    chunk_id      uuid    NOT NULL,
    document_id   uuid    NOT NULL,
    chunk_sha256  text    NOT NULL,
    defining      boolean NOT NULL,
    CONSTRAINT pk_system_mention PRIMARY KEY (entity_id, chunk_id),
    CONSTRAINT fk_system_mention_entity FOREIGN KEY (entity_id) REFERENCES document.system_entity (id) ON DELETE CASCADE,
    CONSTRAINT fk_system_mention_chunk FOREIGN KEY (chunk_id) REFERENCES document.document_chunk (id) ON DELETE CASCADE,
    CONSTRAINT fk_system_mention_document FOREIGN KEY (document_id) REFERENCES document.document (id) ON DELETE CASCADE,
    CONSTRAINT ck_system_mention_sha256 CHECK (chunk_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX idx_system_mention_chunk ON document.system_mention (chunk_id);
CREATE INDEX idx_system_mention_document ON document.system_mention (document_id);

-- Which documents have a current model: the extractor version and the digest of the chunk hashes it read. The backfill
-- job extracts READY documents without a row or with another version (SystemModelPlan).
CREATE TABLE document.system_model_state (
    document_id        uuid        NOT NULL,
    extractor_version  integer     NOT NULL,
    chunks_sha256      text        NOT NULL,
    entities           integer     NOT NULL,
    relations          integer     NOT NULL,
    truncated          boolean     NOT NULL,
    extracted_at       timestamptz NOT NULL,
    CONSTRAINT pk_system_model_state PRIMARY KEY (document_id),
    CONSTRAINT fk_system_model_state_document FOREIGN KEY (document_id) REFERENCES document.document (id) ON DELETE CASCADE,
    CONSTRAINT ck_system_model_state_version CHECK (extractor_version >= 1),
    CONSTRAINT ck_system_model_state_sha256 CHECK (chunks_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_system_model_state_counts CHECK (entities >= 0 AND relations >= 0)
);
-- No further index: the backfill and the counts probe this table by its primary key from document rows.

-- Replaced as a whole, never changed in place (like chunks, V1): no UPDATE for the application role.
REVOKE UPDATE ON document.system_entity FROM svc_document;
REVOKE UPDATE ON document.system_relation FROM svc_document;
REVOKE UPDATE ON document.system_mention FROM svc_document;
REVOKE UPDATE ON document.system_model_state FROM svc_document;
