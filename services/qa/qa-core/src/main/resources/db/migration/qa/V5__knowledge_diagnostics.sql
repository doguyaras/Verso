-- Company diagnostics ("bilgi sağlığı") and hooks of the learning knowledge layer (ADR-0048 phase 2, ADR-0052). Owner:
-- the qa module, schema "qa", migrated as svc_qa_migrate and used as svc_qa. Document and chunk ids are plain uuid
-- columns without a foreign key: documents live in the "document" schema, which this module never reaches (AGENTS.md
-- section 4); the diagnostics read them through document-api (DocumentDiagnostics, DocumentProvenance).
--
-- Privacy (ADR-0048 k reconciliation, ADR-0052, docs/uyum): nothing here holds an account id. A question cluster is an
-- aggregate that exists only with at least 5 distinct askers in its window (CHECK below: no setting can lower it);
-- its keywords were used by at least that many askers; its representative questions are hashes, resolved to text only
-- under the installation's question-text policy (VERSO_SHOW_UNANSWERED_QUESTIONS). A question vector is derived from a
-- question and goes with its turn (history retention, the owner's deletion, a KVKK erasure). Contradiction candidates
-- keep ids, pages and hashes only: the differing values are read again, with the admin's own principals, when shown.
-- Notifications name a subject by id and a fixed word. Nothing here is logged.
--
-- contract: none in practice. It drops and re-adds ck_eval_run_trigger and ck_knowledge_card_stale_reason with one more
-- value each (HOOK, CONTRADICTION). The previous image never writes the new values, so it keeps running on this schema
-- and a rollback to it needs no schema change; only rows it never wrote could fail its enum mapping (none exist until
-- this image runs a hook).
--
-- Rollout: one nullable column on qa.turn (metadata only), one partial index on qa.turn (built inside the migration,
-- short write lock on a table bounded by the history retention), new empty tables and seeded hook rules. Rollback:
-- the old image ignores all of it; DROP the new tables, the index and the column once the feature is given up.

-- The groundedness check's verdict of a stored answer (ADR-0047): how many cited sentences it could not verify; null
-- when the check was off. A flagged answer is an uncertain one for the diagnostics. Written once with the turn.
ALTER TABLE qa.turn ADD COLUMN unverified_sentences integer CONSTRAINT ck_turn_unverified_sentences
    CHECK (unverified_sentences >= 0);

-- The diagnostics' input: the uncertain turns of a day range (NOT_FOUND, UNCITED or grounding-flagged).
CREATE INDEX idx_turn_uncertain_day ON qa.turn (day, id)
    WHERE outcome IN ('NOT_FOUND', 'UNCITED') OR unverified_sentences > 0;

-- The question of an uncertain turn embedded by the diagnostics job (offline, never on the hot path), so each question
-- is embedded once. Derived from the question: deleted with the turn.
CREATE TABLE qa.turn_question_vector (
    turn_id          uuid                    NOT NULL,
    embedding        extensions.vector(1024) NOT NULL,
    embedding_model  text                    NOT NULL,
    created_at       timestamptz             NOT NULL DEFAULT now(),
    CONSTRAINT pk_turn_question_vector PRIMARY KEY (turn_id),
    CONSTRAINT fk_turn_question_vector_turn FOREIGN KEY (turn_id) REFERENCES qa.turn (id) ON DELETE CASCADE
);

-- One run of the diagnostics job. At most one RUNNING row in the installation (index below): the distributed lock, with
-- a lease so a crashed instance's run is taken over. The run's aggregates (stale documents, coverage, card counts,
-- down-rating reasons by collection) are a JSON snapshot of ids and counts, every cell already k-anonymous.
CREATE TABLE qa.diagnostic_run (
    id                      uuid        NOT NULL DEFAULT uuidv7(),
    run_trigger             text        NOT NULL,
    run_day                 date        NOT NULL,
    status                  text        NOT NULL,
    claim_token             uuid,
    locked_until            timestamptz,
    window_from             date        NOT NULL,
    window_to               date        NOT NULL,
    min_askers              integer     NOT NULL,
    questions               integer     NOT NULL DEFAULT 0,
    embedded                integer     NOT NULL DEFAULT 0,
    clusters                integer     NOT NULL DEFAULT 0,
    contradictions_checked  integer     NOT NULL DEFAULT 0,
    -- The last chunk the contradiction scan reached; the next run continues after it (null: from the start).
    contradiction_cursor    uuid,
    summary                 jsonb,
    started_at              timestamptz NOT NULL DEFAULT now(),
    finished_at             timestamptz,
    CONSTRAINT pk_diagnostic_run PRIMARY KEY (id),
    CONSTRAINT ck_diagnostic_run_trigger CHECK (run_trigger IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT ck_diagnostic_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_diagnostic_run_claim CHECK ((status = 'RUNNING') = (claim_token IS NOT NULL)),
    CONSTRAINT ck_diagnostic_run_window CHECK (window_from <= window_to),
    -- The k of multi-dimensional diagnostics (ADR-0048): 5 or more, never less.
    CONSTRAINT ck_diagnostic_run_min_askers CHECK (min_askers >= 5),
    CONSTRAINT ck_diagnostic_run_counts CHECK (questions >= 0 AND embedded >= 0 AND clusters >= 0
        AND contradictions_checked >= 0)
);
CREATE UNIQUE INDEX uq_diagnostic_run_running ON qa.diagnostic_run ((true)) WHERE status = 'RUNNING';
CREATE UNIQUE INDEX uq_diagnostic_run_scheduled_day ON qa.diagnostic_run (run_day)
    WHERE run_trigger = 'SCHEDULED' AND status <> 'FAILED';
-- The report: the latest successful run whose window ends in the requested range; the trend series of the range.
CREATE INDEX idx_diagnostic_run_window ON qa.diagnostic_run (status, window_to DESC, id DESC);

-- A cluster of uncertain questions of one run: a size, how many distinct accounts asked, the trend against the previous
-- run, keywords and representative question hashes. No account id, no question text.
CREATE TABLE qa.question_cluster (
    id                     uuid                    NOT NULL DEFAULT uuidv7(),
    run_id                 uuid                    NOT NULL,
    position               integer                 NOT NULL,
    -- The mean question vector: matches this cluster with the previous run's (trend) and the next run's.
    centroid               extensions.vector(1024) NOT NULL,
    embedding_model        text                    NOT NULL,
    turns                  integer                 NOT NULL,
    askers                 integer                 NOT NULL,
    previous_turns         integer,
    trend                  text                    NOT NULL,
    -- Lexemes that at least min_askers distinct accounts of the cluster used (k-anonymous, from tsvector statistics).
    keywords               text[]                  NOT NULL DEFAULT '{}',
    -- Normalised question hashes (QuestionNormalizer) that at least min_askers distinct accounts asked: the text is
    -- read from the history only under the question-text policy, and is gone with the history retention.
    representative_hashes  bytea[]                 NOT NULL DEFAULT '{}',
    CONSTRAINT pk_question_cluster PRIMARY KEY (id),
    CONSTRAINT fk_question_cluster_run FOREIGN KEY (run_id) REFERENCES qa.diagnostic_run (id) ON DELETE CASCADE,
    CONSTRAINT uq_question_cluster_position UNIQUE (run_id, position),
    -- k >= 5 per cell (ADR-0048): a cluster below it is never stored.
    CONSTRAINT ck_question_cluster_askers CHECK (askers >= 5 AND turns >= askers),
    CONSTRAINT ck_question_cluster_trend CHECK (trend IN ('NEW', 'GROWING', 'STABLE', 'SHRINKING')),
    CONSTRAINT ck_question_cluster_previous CHECK (previous_turns IS NULL OR previous_turns >= 1),
    CONSTRAINT ck_question_cluster_keywords CHECK (cardinality(keywords) <= 10),
    CONSTRAINT ck_question_cluster_representatives CHECK (cardinality(representative_hashes) <= 5)
);

-- Two chunks of different shared documents that say nearly the same thing with different numbers or codes. Found
-- offline (capped per run); ids, pages and hashes only. Gone when either document changes or when not seen again
-- within the contradiction retention.
CREATE TABLE qa.contradiction_candidate (
    id             uuid         NOT NULL DEFAULT uuidv7(),
    chunk_a        uuid         NOT NULL,
    document_a     uuid         NOT NULL,
    page_a         integer      NOT NULL,
    hash_a         text         NOT NULL,
    chunk_b        uuid         NOT NULL,
    document_b     uuid         NOT NULL,
    page_b         integer      NOT NULL,
    hash_b         text         NOT NULL,
    similarity     numeric(5,4) NOT NULL,
    differences    integer      NOT NULL,
    first_seen_at  timestamptz  NOT NULL DEFAULT now(),
    last_seen_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_contradiction_candidate PRIMARY KEY (id),
    -- One row per unordered pair.
    CONSTRAINT uq_contradiction_candidate_pair UNIQUE (chunk_a, chunk_b),
    CONSTRAINT ck_contradiction_candidate_order CHECK (chunk_a < chunk_b),
    CONSTRAINT ck_contradiction_candidate_documents CHECK (document_a <> document_b),
    CONSTRAINT ck_contradiction_candidate_pages CHECK (page_a >= 1 AND page_b >= 1),
    CONSTRAINT ck_contradiction_candidate_hashes CHECK (hash_a ~ '^[0-9a-f]{64}$' AND hash_b ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_contradiction_candidate_values CHECK (similarity BETWEEN 0 AND 1 AND differences >= 1)
);
CREATE INDEX idx_contradiction_candidate_document_a ON qa.contradiction_candidate (document_a);
CREATE INDEX idx_contradiction_candidate_document_b ON qa.contradiction_candidate (document_b);
CREATE INDEX idx_contradiction_candidate_seen ON qa.contradiction_candidate (last_seen_at);

-- The hook rules (ADR-0048 principle 5): a trigger and one allowed action. The set of rules is fixed by migrations;
-- admins only switch a rule on or off. The CHECK on action is the hard boundary: a hook can notify, flag, demote or
-- run the golden-set check, never promote, delete or change access.
CREATE TABLE qa.hook_rule (
    rule_key         text        NOT NULL,
    trigger_type     text        NOT NULL,
    action           text        NOT NULL,
    enabled          boolean     NOT NULL,
    default_enabled  boolean     NOT NULL,
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_hook_rule PRIMARY KEY (rule_key),
    CONSTRAINT ck_hook_rule_key CHECK (rule_key ~ '^[A-Z][A-Z0-9_]{2,39}$'),
    CONSTRAINT ck_hook_rule_trigger CHECK (trigger_type IN ('DOCUMENT_CHANGED', 'CLUSTER_EMERGED', 'CLUSTER_GROWING',
        'CONTRADICTION_FOUND', 'CARD_STALE', 'CARD_DISPUTED', 'GOLDEN_RECALL_DROP', 'VERIFICATION_DUE')),
    CONSTRAINT ck_hook_rule_action CHECK (action IN ('NOTIFY', 'FLAG', 'DEMOTE', 'RUN_EVAL'))
);
INSERT INTO qa.hook_rule (rule_key, trigger_type, action, enabled, default_enabled) VALUES
    ('DOCUMENT_CHANGED_RUN_EVAL', 'DOCUMENT_CHANGED', 'RUN_EVAL', true, true),
    ('CLUSTER_EMERGED_NOTIFY', 'CLUSTER_EMERGED', 'NOTIFY', true, true),
    ('CLUSTER_GROWING_NOTIFY', 'CLUSTER_GROWING', 'NOTIFY', true, true),
    ('CONTRADICTION_FLAG', 'CONTRADICTION_FOUND', 'FLAG', true, true),
    ('CONTRADICTION_DEMOTE_CARD', 'CONTRADICTION_FOUND', 'DEMOTE', false, false),
    ('CARD_STALE_NOTIFY', 'CARD_STALE', 'NOTIFY', true, true),
    ('CARD_DISPUTED_FLAG', 'CARD_DISPUTED', 'FLAG', true, true),
    ('GOLDEN_RECALL_DROP_NOTIFY', 'GOLDEN_RECALL_DROP', 'NOTIFY', true, true),
    ('VERIFICATION_DUE_NOTIFY', 'VERIFICATION_DUE', 'NOTIFY', true, true);

-- An in-app notice for the installation's admins (NOTIFY) or an item waiting for review (FLAG): a rule, a subject by id
-- and a fixed word. Shared by every admin (one read state). One row per dedupe key: a repeated event adds nothing.
CREATE TABLE qa.hook_notification (
    id            uuid        NOT NULL DEFAULT uuidv7(),
    rule_key      text        NOT NULL,
    action        text        NOT NULL,
    trigger_type  text        NOT NULL,
    subject_type  text        NOT NULL,
    subject_id    uuid        NOT NULL,
    detail        text,
    severity      text        NOT NULL,
    dedupe_key    text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    read_at       timestamptz,
    CONSTRAINT pk_hook_notification PRIMARY KEY (id),
    CONSTRAINT uq_hook_notification_dedupe UNIQUE (dedupe_key),
    CONSTRAINT ck_hook_notification_rule CHECK (rule_key ~ '^[A-Z][A-Z0-9_]{2,39}$'),
    CONSTRAINT ck_hook_notification_action CHECK (action IN ('NOTIFY', 'FLAG')),
    CONSTRAINT ck_hook_notification_subject CHECK (subject_type IN ('DOCUMENT', 'KNOWLEDGE_CARD', 'QUESTION_CLUSTER',
        'CONTRADICTION', 'EVAL_RUN')),
    CONSTRAINT ck_hook_notification_detail CHECK (detail IS NULL OR detail ~ '^[A-Z0-9_]{1,40}$'),
    CONSTRAINT ck_hook_notification_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    CONSTRAINT ck_hook_notification_dedupe CHECK (char_length(dedupe_key) BETWEEN 1 AND 200)
);
CREATE INDEX idx_hook_notification_created ON qa.hook_notification (created_at DESC, id DESC);
CREATE INDEX idx_hook_notification_unread ON qa.hook_notification (created_at DESC) WHERE read_at IS NULL;

-- A golden-set check a hook asked for (RUN_EVAL): one pending request for the installation, so a connector that
-- replaces 500 documents asks once; the scheduler runs it after a quiet period.
CREATE TABLE qa.hook_eval_request (
    singleton     boolean     NOT NULL DEFAULT true,
    rule_key      text        NOT NULL,
    requested_at  timestamptz NOT NULL,
    CONSTRAINT pk_hook_eval_request PRIMARY KEY (singleton),
    CONSTRAINT ck_hook_eval_request_singleton CHECK (singleton)
);

-- A golden-set check started by a hook (system actor) and a card demoted by a contradiction hook.
ALTER TABLE qa.eval_run DROP CONSTRAINT ck_eval_run_trigger;
ALTER TABLE qa.eval_run ADD CONSTRAINT ck_eval_run_trigger CHECK (run_trigger IN ('SCHEDULED', 'MANUAL', 'HOOK'));
ALTER TABLE qa.knowledge_card DROP CONSTRAINT ck_knowledge_card_stale_reason;
ALTER TABLE qa.knowledge_card ADD CONSTRAINT ck_knowledge_card_stale_reason CHECK (stale_reason IN
    ('SOURCE_DELETED', 'SOURCE_REPLACED', 'SOURCE_MOVED', 'SOURCE_CHANGED', 'EXPIRED', 'CONTRADICTION'));

-- Vectors and clusters are written once; a cluster changes only with its run (deleted by the run retention).
REVOKE UPDATE ON qa.turn_question_vector FROM svc_qa;
REVOKE UPDATE ON qa.question_cluster FROM svc_qa;
-- The rule set is the migration's: the application switches rules on and off, it cannot add or remove one.
REVOKE INSERT, DELETE ON qa.hook_rule FROM svc_qa;
