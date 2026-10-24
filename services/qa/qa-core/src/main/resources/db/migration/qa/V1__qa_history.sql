-- Conversation history, answer feedback and usage rollups of the qa module (ADR-0023). Owner: the qa module, schema
-- "qa", migrated as svc_qa_migrate and used as svc_qa (reference 10.1). Document ids are plain uuid columns without a
-- foreign key: documents live in the "document" schema, which this module never reaches (AGENTS.md section 4); a
-- citation of a deleted document stays and is shown as deleted (DocumentCatalog). Ids are UUIDv7 from PostgreSQL 18
-- (reference 10.3).

-- One conversation of one account. Removed by its owner, or by the retention job retention-days after it was started
-- (with every turn, so that its title, the start of the first question, never outlives the retention).
CREATE TABLE qa.conversation (
    id          uuid        NOT NULL DEFAULT uuidv7(),
    -- The validated token's sub (ADR-0005); every query of the API filters on it.
    account_id  text        NOT NULL,
    -- The caller's own text (start of the first question, or a rename); never logged (llm-rules 2.1).
    title       text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- Time of the last turn (a rename leaves it); the list is ordered by it.
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_conversation PRIMARY KEY (id),
    CONSTRAINT ck_conversation_title CHECK (char_length(title) BETWEEN 1 AND 120)
);
-- GET /v1/conversations: the caller's conversations, last activity first.
CREATE INDEX idx_conversation_account_updated ON qa.conversation (account_id, updated_at DESC, id DESC);
-- Retention job: conversations started before the cutoff.
CREATE INDEX idx_conversation_created ON qa.conversation (created_at);

-- One question and its answer. Written once, never updated (no UPDATE for the application role below).
CREATE TABLE qa.turn (
    id               uuid        NOT NULL DEFAULT uuidv7(),
    conversation_id  uuid        NOT NULL,
    -- Copied from the conversation: ownership checks and the k-anonymity count need no join.
    account_id       text        NOT NULL,
    -- Question and answer text: personal data, kept for the retention period only and never logged (llm-rules 2.1).
    question         text        NOT NULL,
    -- SHA-256 of the normalised question (lower case, single spaces): groups equal "not found" questions for the
    -- k-anonymous list without comparing texts. Not a pseudonym: the text sits in the same row.
    question_hash    bytea       NOT NULL,
    answer           text        NOT NULL,
    outcome          text        NOT NULL,
    mode             text        NOT NULL,
    chat_model       text        NOT NULL,
    -- Server-side timings: total_ms feeds the latency rollup and the API; retrieval and chat split it for diagnosis.
    retrieval_ms     integer     NOT NULL,
    chat_ms          integer     NOT NULL,
    total_ms         integer     NOT NULL,
    -- The calendar day in the metrics time zone (verso.qa.metrics.time-zone); feedback is counted on this day.
    day              date        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_turn PRIMARY KEY (id),
    CONSTRAINT fk_turn_conversation FOREIGN KEY (conversation_id) REFERENCES qa.conversation (id) ON DELETE CASCADE,
    CONSTRAINT ck_turn_outcome CHECK (outcome IN ('ANSWERED', 'NOT_FOUND', 'UNCITED')),
    CONSTRAINT ck_turn_mode CHECK (mode IN ('local', 'cloud')),
    CONSTRAINT ck_turn_question CHECK (char_length(question) BETWEEN 1 AND 1000),
    CONSTRAINT ck_turn_question_hash CHECK (octet_length(question_hash) = 32),
    CONSTRAINT ck_turn_answer CHECK (char_length(answer) <= 32000),
    CONSTRAINT ck_turn_timings CHECK (retrieval_ms >= 0 AND chat_ms >= 0 AND total_ms >= 0)
);
-- GET /v1/conversations/{id}: the turns of one conversation, oldest first; also the retention job's and the cascade's
-- way from a conversation to its turns.
CREATE INDEX idx_turn_conversation_created ON qa.turn (conversation_id, created_at, id);
-- Admin metrics: "not found" questions of a day range, grouped by their hash (k-anonymity).
CREATE INDEX idx_turn_not_found_day ON qa.turn (day, question_hash) WHERE outcome = 'NOT_FOUND';

-- Citation references of a turn (llm-rules 3.3): number, document and page or section; no passage text.
CREATE TABLE qa.turn_citation (
    turn_id          uuid    NOT NULL,
    citation_number  integer NOT NULL,
    document_id      uuid    NOT NULL,
    page             integer NOT NULL,
    unit             text    NOT NULL,
    CONSTRAINT pk_turn_citation PRIMARY KEY (turn_id, citation_number),
    CONSTRAINT fk_turn_citation_turn FOREIGN KEY (turn_id) REFERENCES qa.turn (id) ON DELETE CASCADE,
    CONSTRAINT ck_turn_citation_number CHECK (citation_number >= 1),
    CONSTRAINT ck_turn_citation_page CHECK (page >= 1),
    CONSTRAINT ck_turn_citation_unit CHECK (unit IN ('PAGE', 'SECTION'))
);

-- The owner's rating of one turn: one row per turn, rating again replaces it (ADR-0023). Gone with the turn.
CREATE TABLE qa.turn_feedback (
    turn_id          uuid        NOT NULL,
    rating           text        NOT NULL,
    reason           text,
    citation_number  integer,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_turn_feedback PRIMARY KEY (turn_id),
    CONSTRAINT fk_turn_feedback_turn FOREIGN KEY (turn_id) REFERENCES qa.turn (id) ON DELETE CASCADE,
    CONSTRAINT ck_turn_feedback_rating CHECK (rating IN ('UP', 'DOWN')),
    CONSTRAINT ck_turn_feedback_reason CHECK (reason IN ('WRONG_SOURCE', 'INCORRECT_OR_MISSING', 'OUTDATED', 'OTHER')),
    -- A reason only with a down rating; a citation number exactly with WRONG_SOURCE.
    CONSTRAINT ck_turn_feedback_reason_down CHECK (rating = 'DOWN' OR reason IS NULL),
    CONSTRAINT ck_turn_feedback_citation CHECK
        ((reason IS NOT DISTINCT FROM 'WRONG_SOURCE') = (citation_number IS NOT NULL)),
    CONSTRAINT ck_turn_feedback_citation_number CHECK (citation_number >= 1)
);

-- Usage rollups (ADR-0023): counters per day in the metrics time zone, raised in the transaction that stores the turn
-- or the rating (atomic upserts, safe with several instances). Counts only, no account and no text: they outlive the
-- history retention and a user's deletion of their history, and the application role cannot delete them.
CREATE TABLE qa.usage_daily (
    day           date   NOT NULL,
    questions     bigint NOT NULL DEFAULT 0,
    answered      bigint NOT NULL DEFAULT 0,
    not_found     bigint NOT NULL DEFAULT 0,
    uncited       bigint NOT NULL DEFAULT 0,
    local_mode    bigint NOT NULL DEFAULT 0,
    cloud_mode    bigint NOT NULL DEFAULT 0,
    active_users  bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_usage_daily PRIMARY KEY (day),
    CONSTRAINT ck_usage_daily_counts CHECK (questions >= 0 AND answered >= 0 AND not_found >= 0 AND uncited >= 0
        AND local_mode >= 0 AND cloud_mode >= 0 AND active_users >= 0)
);

-- Answer time histogram per day: bucket_ms is the bucket's upper bound; p50/p95 of any day range are read from it.
CREATE TABLE qa.usage_daily_latency (
    day        date    NOT NULL,
    bucket_ms  integer NOT NULL,
    turns      bigint  NOT NULL,
    CONSTRAINT pk_usage_daily_latency PRIMARY KEY (day, bucket_ms),
    CONSTRAINT ck_usage_daily_latency_turns CHECK (turns >= 0)
);

-- Citations per document and day: top cited documents of a range, and the documents never cited.
CREATE TABLE qa.usage_daily_document (
    day          date   NOT NULL,
    document_id  uuid   NOT NULL,
    citations    bigint NOT NULL,
    CONSTRAINT pk_usage_daily_document PRIMARY KEY (day, document_id),
    CONSTRAINT ck_usage_daily_document_citations CHECK (citations >= 0)
);
-- Every document ever cited (admin metrics), read without the day.
CREATE INDEX idx_usage_daily_document_document ON qa.usage_daily_document (document_id);

-- Ratings per day, rating and reason ('NONE' for a rating without reason); a new rating moves one count.
CREATE TABLE qa.usage_daily_feedback (
    day       date   NOT NULL,
    rating    text   NOT NULL,
    reason    text   NOT NULL,
    feedback  bigint NOT NULL,
    CONSTRAINT pk_usage_daily_feedback PRIMARY KEY (day, rating, reason),
    CONSTRAINT ck_usage_daily_feedback_rating CHECK (rating IN ('UP', 'DOWN')),
    CONSTRAINT ck_usage_daily_feedback_reason CHECK
        (reason IN ('NONE', 'WRONG_SOURCE', 'INCORRECT_OR_MISSING', 'OUTDATED', 'OTHER')),
    CONSTRAINT ck_usage_daily_feedback_count CHECK (feedback >= 0)
);

-- Which accounts asked on which day: counts distinct active users exactly, per day and over a range. Personal data
-- (an account id): kept as long as the history (retention job) and deleted with "delete all my conversations".
CREATE TABLE qa.active_account_day (
    day         date NOT NULL,
    account_id  text NOT NULL,
    CONSTRAINT pk_active_account_day PRIMARY KEY (day, account_id)
);
-- Deleting one account's rows ("delete all mine").
CREATE INDEX idx_active_account_day_account ON qa.active_account_day (account_id);

-- Turns and citations are written and deleted, never changed (phase 4 db review D6 applied here as well).
REVOKE UPDATE ON qa.turn FROM svc_qa;
REVOKE UPDATE ON qa.turn_citation FROM svc_qa;
REVOKE UPDATE ON qa.active_account_day FROM svc_qa;
-- Citations and ratings are deleted only with their turn (ON DELETE CASCADE, which runs with the owner's rights).
REVOKE DELETE ON qa.turn_citation FROM svc_qa;
REVOKE DELETE ON qa.turn_feedback FROM svc_qa;
-- Rollups only grow: a compromised application cannot erase usage history (counts are corrected by UPDATE only).
REVOKE DELETE ON qa.usage_daily FROM svc_qa;
REVOKE DELETE ON qa.usage_daily_latency FROM svc_qa;
REVOKE DELETE ON qa.usage_daily_document FROM svc_qa;
REVOKE DELETE ON qa.usage_daily_feedback FROM svc_qa;
