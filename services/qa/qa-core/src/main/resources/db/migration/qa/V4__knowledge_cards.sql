-- Answer memory, per-customer golden set and its nightly check (ADR-0048 phase 1, ADR-0049). Owner: the qa module,
-- schema "qa", migrated as svc_qa_migrate and used as svc_qa. Document and chunk ids are plain uuid columns without a
-- foreign key: documents live in the "document" schema, which this module never reaches (AGENTS.md section 4); a card
-- learns about a changed document through the document outbox (DocumentChangeListener) and checks its hashes through
-- document-api (DocumentProvenance), never by a join.
--
-- Privacy (ADR-0049, docs/uyum): a card holds an approved question and answer text (company knowledge, possibly quoting
-- a document) and the account ids of its creator and verifiers (accountability, like the audit log). A dispute holds
-- no account id: only a per-card SHA-256 of it, so one user disputes a card once and no one can list who disputed.
-- Nothing here is logged.
--
-- Rollout: new, empty tables; the old image never touches them. Rollback: the old image ignores them; DROP the tables
-- below (children first) once the feature is given up. A dropped card is a lost verification, not a lost answer.

-- A verified answer card: a question, the approved answer (with its [n] markers) and its state.
CREATE TABLE qa.knowledge_card (
    id                  uuid                    NOT NULL DEFAULT uuidv7(),
    -- The canonical question: what new questions are matched against (and the golden case's question).
    question            text                    NOT NULL,
    -- SHA-256 of the normalised question (QuestionNormalizer): one live card per question (index below).
    question_hash       bytea                   NOT NULL,
    -- The canonical question embedded with the documents' local model (ADR-0049: one embedding per question on the
    -- hot path, reused for the lookup). A card is matched only while embedding_model is the current model.
    question_embedding  extensions.vector(1024) NOT NULL,
    embedding_model     text                    NOT NULL,
    -- The approved answer as stored in the source turn, markers included.
    answer              text                    NOT NULL,
    key_facts           text[]                  NOT NULL DEFAULT '{}',
    state               text                    NOT NULL,
    stale_reason        text,
    -- The collections of the cited documents when the card was created (display and audit of the scope). The serving
    -- check does not trust it: it re-reads every cited chunk with the caller's own principals (ADR-0028).
    scope_collections   uuid[]                  NOT NULL,
    -- The turn it was created from; no foreign key: the history retention deletes turns, the card outlives them.
    source_turn_id      uuid,
    -- The validated token's sub of the admin who created it (accountability; never returned by the API).
    created_by          text                    NOT NULL,
    -- Verification cycle: a re-verification after expiry or a dispute starts a new one (verifiers and disputes count
    -- per cycle).
    cycle               integer                 NOT NULL DEFAULT 1,
    required_verifiers  integer                 NOT NULL,
    dispute_count       integer                 NOT NULL DEFAULT 0,
    verified_at         timestamptz,
    expires_at          timestamptz,
    created_at          timestamptz             NOT NULL DEFAULT now(),
    updated_at          timestamptz             NOT NULL DEFAULT now(),
    CONSTRAINT pk_knowledge_card PRIMARY KEY (id),
    CONSTRAINT ck_knowledge_card_question CHECK (char_length(question) BETWEEN 1 AND 1000),
    CONSTRAINT ck_knowledge_card_question_hash CHECK (octet_length(question_hash) = 32),
    CONSTRAINT ck_knowledge_card_answer CHECK (char_length(answer) BETWEEN 1 AND 32000),
    CONSTRAINT ck_knowledge_card_key_facts CHECK (cardinality(key_facts) <= 10),
    CONSTRAINT ck_knowledge_card_state CHECK (state IN ('DRAFT', 'VERIFIED', 'STALE', 'DISPUTED', 'ARCHIVED')),
    CONSTRAINT ck_knowledge_card_stale_reason CHECK (stale_reason IN
        ('SOURCE_DELETED', 'SOURCE_REPLACED', 'SOURCE_MOVED', 'SOURCE_CHANGED', 'EXPIRED')),
    -- A reason exactly while stale.
    CONSTRAINT ck_knowledge_card_stale CHECK ((state = 'STALE') = (stale_reason IS NOT NULL)),
    -- A served card always has its verification time and its expiry.
    CONSTRAINT ck_knowledge_card_verified CHECK (state <> 'VERIFIED' OR (verified_at IS NOT NULL AND expires_at IS NOT NULL)),
    CONSTRAINT ck_knowledge_card_scope CHECK (cardinality(scope_collections) BETWEEN 1 AND 50),
    CONSTRAINT ck_knowledge_card_created_by CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT ck_knowledge_card_counts CHECK (cycle >= 1 AND required_verifiers BETWEEN 1 AND 2 AND dispute_count >= 0)
);
-- One live card per normalised question: a second approval of the same question is a conflict, not a silent twin
-- (two verified answers with different conclusions would be served by similarity alone).
CREATE UNIQUE INDEX uq_knowledge_card_live_question ON qa.knowledge_card (question_hash) WHERE state <> 'ARCHIVED';
-- The admin list by state, newest change first.
CREATE INDEX idx_knowledge_card_state_updated ON qa.knowledge_card (state, updated_at DESC, id DESC);
-- The serving lookup reads VERIFIED cards only and compares every one exactly (no vector index: a customer keeps
-- hundreds to a few thousand cards, an exact scan of them is milliseconds and never misses a neighbour). Revisit with
-- an HNSW index when the VERIFIED count passes 10 000.

-- The citations of the approved answer (number, document, page), as the turn had them.
CREATE TABLE qa.knowledge_card_citation (
    card_id          uuid    NOT NULL,
    citation_number  integer NOT NULL,
    document_id      uuid    NOT NULL,
    page             integer NOT NULL,
    unit             text    NOT NULL,
    CONSTRAINT pk_knowledge_card_citation PRIMARY KEY (card_id, citation_number),
    CONSTRAINT fk_knowledge_card_citation_card FOREIGN KEY (card_id) REFERENCES qa.knowledge_card (id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_card_citation_number CHECK (citation_number BETWEEN 1 AND 100),
    CONSTRAINT ck_knowledge_card_citation_page CHECK (page >= 1),
    CONSTRAINT ck_knowledge_card_citation_unit CHECK (unit IN ('PAGE', 'SECTION'))
);
-- A changed document: every card citing it.
CREATE INDEX idx_knowledge_card_citation_document ON qa.knowledge_card_citation (document_id);

-- The hash binding: every chunk of every cited page, with the SHA-256 of its text when the card was created.
CREATE TABLE qa.knowledge_card_source (
    card_id         uuid    NOT NULL,
    chunk_id        uuid    NOT NULL,
    document_id     uuid    NOT NULL,
    page            integer NOT NULL,
    content_sha256  text    NOT NULL,
    CONSTRAINT pk_knowledge_card_source PRIMARY KEY (card_id, chunk_id),
    CONSTRAINT fk_knowledge_card_source_card FOREIGN KEY (card_id) REFERENCES qa.knowledge_card (id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_card_source_page CHECK (page >= 1),
    CONSTRAINT ck_knowledge_card_source_hash CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);

-- Who verified in which cycle (two-person rule: distinct accounts).
CREATE TABLE qa.knowledge_card_verification (
    card_id      uuid        NOT NULL,
    cycle        integer     NOT NULL,
    verifier_id  text        NOT NULL,
    verified_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_knowledge_card_verification PRIMARY KEY (card_id, cycle, verifier_id),
    CONSTRAINT fk_knowledge_card_verification_card FOREIGN KEY (card_id) REFERENCES qa.knowledge_card (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_card_verification_verifier CHECK (char_length(verifier_id) BETWEEN 1 AND 255)
);

-- One dispute per user, card and cycle: a down rating of an answer served from the card. account_hash is
-- SHA-256('verso:dispute:' || card id || ':' || sub), different for every card, so rows cannot be linked across cards.
CREATE TABLE qa.knowledge_card_dispute (
    card_id       uuid        NOT NULL,
    cycle         integer     NOT NULL,
    account_hash  bytea       NOT NULL,
    reason        text        NOT NULL,
    day           date        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_knowledge_card_dispute PRIMARY KEY (card_id, cycle, account_hash),
    CONSTRAINT fk_knowledge_card_dispute_card FOREIGN KEY (card_id) REFERENCES qa.knowledge_card (id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_card_dispute_hash CHECK (octet_length(account_hash) = 32),
    CONSTRAINT ck_knowledge_card_dispute_reason CHECK
        (reason IN ('NONE', 'WRONG_SOURCE', 'INCORRECT_OR_MISSING', 'OUTDATED', 'OTHER'))
);

-- The state history of a card: what changed and why (who: the audit log).
CREATE TABLE qa.knowledge_card_event (
    id          uuid        NOT NULL DEFAULT uuidv7(),
    card_id     uuid        NOT NULL,
    from_state  text,
    to_state    text        NOT NULL,
    reason      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_knowledge_card_event PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_card_event_card FOREIGN KEY (card_id) REFERENCES qa.knowledge_card (id) ON DELETE CASCADE,
    CONSTRAINT ck_knowledge_card_event_reason CHECK (reason ~ '^[A-Z_]{1,40}$')
);
CREATE INDEX idx_knowledge_card_event_card ON qa.knowledge_card_event (card_id, created_at, id);

-- Which stored turn was answered from which card: a down rating of that turn is a dispute of the card. Gone with the
-- turn (history retention, the owner's deletion, a KVKK erasure).
CREATE TABLE qa.turn_knowledge_card (
    turn_id  uuid NOT NULL,
    card_id  uuid NOT NULL,
    cycle    integer NOT NULL,
    CONSTRAINT pk_turn_knowledge_card PRIMARY KEY (turn_id),
    CONSTRAINT fk_turn_knowledge_card_turn FOREIGN KEY (turn_id) REFERENCES qa.turn (id) ON DELETE CASCADE,
    CONSTRAINT fk_turn_knowledge_card_card FOREIGN KEY (card_id) REFERENCES qa.knowledge_card (id) ON DELETE CASCADE
);
CREATE INDEX idx_turn_knowledge_card_card ON qa.turn_knowledge_card (card_id);

-- The per-customer golden set: one case per card that was ever verified; inactive once the card is archived. The
-- question vector, the expected chunks and their hashes are the card's own (knowledge_card, knowledge_card_source).
CREATE TABLE qa.golden_case (
    card_id      uuid        NOT NULL,
    active       boolean     NOT NULL DEFAULT true,
    exported_at  timestamptz NOT NULL DEFAULT now(),
    retired_at   timestamptz,
    CONSTRAINT pk_golden_case PRIMARY KEY (card_id),
    CONSTRAINT fk_golden_case_card FOREIGN KEY (card_id) REFERENCES qa.knowledge_card (id) ON DELETE CASCADE,
    CONSTRAINT ck_golden_case_retired CHECK (active = (retired_at IS NULL))
);

-- One run of the golden-set check. At most one RUNNING row in the whole installation (index below): the distributed
-- lock of the nightly job, with a lease (locked_until) so a crashed instance's run is taken over.
CREATE TABLE qa.eval_run (
    id               uuid         NOT NULL DEFAULT uuidv7(),
    run_trigger      text         NOT NULL,
    -- The calendar day a SCHEDULED run belongs to (metrics time zone): one scheduled run per day.
    run_day          date         NOT NULL,
    status           text         NOT NULL,
    claim_token      uuid,
    locked_until     timestamptz,
    top_k            integer      NOT NULL,
    cases            integer      NOT NULL DEFAULT 0,
    found            integer      NOT NULL DEFAULT 0,
    missed           integer      NOT NULL DEFAULT 0,
    source_lost      integer      NOT NULL DEFAULT 0,
    skipped          integer      NOT NULL DEFAULT 0,
    recall_at_k      numeric(5,4),
    baseline_recall  numeric(5,4),
    source_drift     numeric(5,4),
    alert            boolean      NOT NULL DEFAULT false,
    started_at       timestamptz  NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    CONSTRAINT pk_eval_run PRIMARY KEY (id),
    CONSTRAINT ck_eval_run_trigger CHECK (run_trigger IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT ck_eval_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_eval_run_claim CHECK ((status = 'RUNNING') = (claim_token IS NOT NULL)),
    CONSTRAINT ck_eval_run_counts CHECK (top_k BETWEEN 1 AND 50 AND cases >= 0 AND found >= 0 AND missed >= 0
        AND source_lost >= 0 AND skipped >= 0),
    CONSTRAINT ck_eval_run_ratios CHECK ((recall_at_k IS NULL OR recall_at_k BETWEEN 0 AND 1)
        AND (baseline_recall IS NULL OR baseline_recall BETWEEN 0 AND 1)
        AND (source_drift IS NULL OR source_drift BETWEEN 0 AND 1))
);
CREATE UNIQUE INDEX uq_eval_run_running ON qa.eval_run ((true)) WHERE status = 'RUNNING';
CREATE UNIQUE INDEX uq_eval_run_scheduled_day ON qa.eval_run (run_day) WHERE run_trigger = 'SCHEDULED' AND status <> 'FAILED';
CREATE INDEX idx_eval_run_started ON qa.eval_run (started_at DESC, id DESC);

-- The result of one case in one run (diagnosis of a recall drop).
CREATE TABLE qa.eval_case_result (
    run_id   uuid    NOT NULL,
    card_id  uuid    NOT NULL,
    outcome  text    NOT NULL,
    rank     integer,
    CONSTRAINT pk_eval_case_result PRIMARY KEY (run_id, card_id),
    CONSTRAINT fk_eval_case_result_run FOREIGN KEY (run_id) REFERENCES qa.eval_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_eval_case_result_card FOREIGN KEY (card_id) REFERENCES qa.knowledge_card (id) ON DELETE CASCADE,
    CONSTRAINT ck_eval_case_result_outcome CHECK (outcome IN ('FOUND', 'MISSED', 'SOURCE_LOST', 'SKIPPED')),
    CONSTRAINT ck_eval_case_result_rank CHECK (rank IS NULL OR rank >= 1)
);
CREATE INDEX idx_eval_case_result_card ON qa.eval_case_result (card_id);

-- Bindings, citations, verifications, disputes and events are written once and never changed by the application.
REVOKE UPDATE ON qa.knowledge_card_citation FROM svc_qa;
REVOKE UPDATE ON qa.knowledge_card_source FROM svc_qa;
REVOKE UPDATE ON qa.knowledge_card_verification FROM svc_qa;
REVOKE UPDATE ON qa.knowledge_card_dispute FROM svc_qa;
REVOKE UPDATE ON qa.knowledge_card_event FROM svc_qa;
REVOKE UPDATE ON qa.turn_knowledge_card FROM svc_qa;
REVOKE UPDATE ON qa.eval_case_result FROM svc_qa;
-- The provenance of a card is deleted only with the card (ON DELETE CASCADE runs with the owner's rights).
REVOKE DELETE ON qa.knowledge_card_citation FROM svc_qa;
REVOKE DELETE ON qa.knowledge_card_source FROM svc_qa;
REVOKE DELETE ON qa.knowledge_card_verification FROM svc_qa;
REVOKE DELETE ON qa.knowledge_card_event FROM svc_qa;
