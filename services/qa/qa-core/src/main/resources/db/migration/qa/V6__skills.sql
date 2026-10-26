-- Skills (procedures) of the learning knowledge layer (ADR-0048 phase 3, ADR-0053). Owner: the qa module, schema "qa",
-- migrated as svc_qa_migrate and used as svc_qa. A skill is an ordered list of short instructions, each bound to the
-- chunks it was assembled from (SHA-256 of their text), with the example questions it answers (triggers) and 2-5
-- golden cases. It is never code: the SKILL.md export holds instructions and citation links only. Document and chunk
-- ids are plain uuid columns without a foreign key: documents live in the "document" schema, which this module never
-- reaches (AGENTS.md section 4); a skill learns about a changed document through the document outbox
-- (DocumentChangeListener) and checks its hashes through document-api (DocumentProvenance), never by a join.
--
-- Privacy (ADR-0053, docs/uyum): a skill holds procedure text derived from shared documents and verified answers
-- (company knowledge, not personal data by design; it may still quote a name that a document quotes) and the account
-- ids of its creator and approvers (accountability, like the audit log and the cards). Personal documents can never
-- back a skill (scope CHECK below: at least one collection, and the service refuses a personal source). Nothing here is
-- logged. A skill follows its sources: a deleted or changed source makes it STALE at once, archived skills are deleted
-- after the archive retention.
--
-- contract: none in practice. It drops and re-adds ck_hook_rule_trigger and ck_hook_notification_subject with one more
-- value each (SKILL_STALE, SKILL). The previous image never writes the new values, so it keeps running on this schema;
-- only its enum mapping of a SKILL_STALE rule row could fail, and that rule is read only by HookRepository.enabledRules
-- of a trigger the old image never fires. A rollback needs no schema change.
--
-- Rollout: new, empty tables, four counters on qa.eval_run with a default (metadata only) and one seeded hook rule.
-- Rollback: the old image ignores all of it; DROP the new tables and columns once the feature is given up.

-- A skill: its current version, state and approval cycle.
CREATE TABLE qa.skill (
    id                  uuid        NOT NULL DEFAULT uuidv7(),
    -- The current version's name (agentskills.io: lower case, digits and hyphens, at most 64): one live skill per name.
    name                text        NOT NULL,
    state               text        NOT NULL,
    stale_reason        text,
    version             integer     NOT NULL DEFAULT 1,
    -- Drafted from a diagnostics cluster or by an admin from chosen cards and turns.
    origin              text        NOT NULL,
    -- The question cluster it was drafted from; no foreign key: clusters go with their run (run retention).
    origin_cluster_id   uuid,
    -- The collections of every source when the version was made: a caller must read all of them (display and audit;
    -- the serving check re-reads every bound chunk with the caller's own principals, ADR-0028).
    scope_collections   uuid[]      NOT NULL,
    -- The validated token's sub of the admin who drafted it (accountability; never returned by the API).
    created_by          text        NOT NULL,
    -- Approval cycle: every new version starts a new one (approvals count per cycle).
    cycle               integer     NOT NULL DEFAULT 1,
    required_approvers  integer     NOT NULL,
    approved_at         timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_skill PRIMARY KEY (id),
    CONSTRAINT ck_skill_name CHECK (char_length(name) BETWEEN 1 AND 64 AND name ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT ck_skill_state CHECK (state IN ('DRAFT', 'APPROVED', 'STALE', 'ARCHIVED')),
    CONSTRAINT ck_skill_stale_reason CHECK (stale_reason IN
        ('SOURCE_DELETED', 'SOURCE_REPLACED', 'SOURCE_MOVED', 'SOURCE_CHANGED')),
    -- A reason exactly while stale.
    CONSTRAINT ck_skill_stale CHECK ((state = 'STALE') = (stale_reason IS NOT NULL)),
    -- A served skill always has its approval time.
    CONSTRAINT ck_skill_approved CHECK (state <> 'APPROVED' OR approved_at IS NOT NULL),
    CONSTRAINT ck_skill_origin CHECK (origin IN ('CLUSTER', 'MANUAL')),
    CONSTRAINT ck_skill_scope CHECK (cardinality(scope_collections) BETWEEN 1 AND 50),
    CONSTRAINT ck_skill_created_by CHECK (char_length(created_by) BETWEEN 1 AND 255),
    CONSTRAINT ck_skill_counts CHECK (version >= 1 AND cycle >= 1 AND required_approvers BETWEEN 1 AND 2)
);
-- One live skill per name: the SKILL.md directory name and the MCP verso_skill key.
CREATE UNIQUE INDEX uq_skill_live_name ON qa.skill (name) WHERE state <> 'ARCHIVED';
-- The admin list by state, newest change first.
CREATE INDEX idx_skill_state_updated ON qa.skill (state, updated_at DESC, id DESC);

-- One version of a skill: written once, never changed (the version history and its diff).
CREATE TABLE qa.skill_version (
    skill_id     uuid        NOT NULL,
    version      integer     NOT NULL,
    name         text        NOT NULL,
    description  text        NOT NULL,
    -- Why the version exists: DRAFTED, EDITED.
    reason       text        NOT NULL,
    -- The optional local-model step ordered and condensed the template text (each step re-checked, ADR-0047).
    condensed    boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_skill_version PRIMARY KEY (skill_id, version),
    CONSTRAINT fk_skill_version_skill FOREIGN KEY (skill_id) REFERENCES qa.skill (id) ON DELETE CASCADE,
    CONSTRAINT ck_skill_version_number CHECK (version >= 1),
    CONSTRAINT ck_skill_version_name CHECK (char_length(name) BETWEEN 1 AND 64 AND name ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    -- agentskills.io: at most 1024 characters; one line (it becomes a YAML scalar of the frontmatter).
    CONSTRAINT ck_skill_version_description CHECK (char_length(description) BETWEEN 1 AND 1024
        AND description !~ '[[:cntrl:]]'),
    CONSTRAINT ck_skill_version_reason CHECK (reason ~ '^[A-Z_]{1,40}$')
);

-- The ordered steps of a version.
CREATE TABLE qa.skill_step (
    skill_id     uuid    NOT NULL,
    version      integer NOT NULL,
    position     integer NOT NULL,
    instruction  text    NOT NULL,
    -- Every number and code of the instruction stands in its sources (ADR-0047 AnchorCheck) when the version was made.
    grounded     boolean NOT NULL,
    CONSTRAINT pk_skill_step PRIMARY KEY (skill_id, version, position),
    CONSTRAINT fk_skill_step_version FOREIGN KEY (skill_id, version) REFERENCES qa.skill_version (skill_id, version)
        ON DELETE CASCADE,
    CONSTRAINT ck_skill_step_position CHECK (position BETWEEN 1 AND 12),
    CONSTRAINT ck_skill_step_instruction CHECK (char_length(instruction) BETWEEN 1 AND 1000)
);

-- The hash binding of a step: the chunks it was assembled from, with the SHA-256 of their text at that time.
CREATE TABLE qa.skill_step_source (
    skill_id        uuid    NOT NULL,
    version         integer NOT NULL,
    position        integer NOT NULL,
    chunk_id        uuid    NOT NULL,
    document_id     uuid    NOT NULL,
    page            integer NOT NULL,
    unit            text    NOT NULL,
    content_sha256  text    NOT NULL,
    CONSTRAINT pk_skill_step_source PRIMARY KEY (skill_id, version, position, chunk_id),
    CONSTRAINT fk_skill_step_source_step FOREIGN KEY (skill_id, version, position)
        REFERENCES qa.skill_step (skill_id, version, position) ON DELETE CASCADE,
    CONSTRAINT ck_skill_step_source_page CHECK (page >= 1),
    CONSTRAINT ck_skill_step_source_unit CHECK (unit IN ('PAGE', 'SECTION')),
    CONSTRAINT ck_skill_step_source_hash CHECK (content_sha256 ~ '^[0-9a-f]{64}$')
);
-- A changed document: every skill bound to it.
CREATE INDEX idx_skill_step_source_document ON qa.skill_step_source (document_id);

-- The example questions of a version ("when to use"), embedded with the documents' local model for the matching.
CREATE TABLE qa.skill_trigger (
    skill_id         uuid                    NOT NULL,
    version          integer                 NOT NULL,
    position         integer                 NOT NULL,
    question         text                    NOT NULL,
    embedding        extensions.vector(1024) NOT NULL,
    embedding_model  text                    NOT NULL,
    CONSTRAINT pk_skill_trigger PRIMARY KEY (skill_id, version, position),
    CONSTRAINT fk_skill_trigger_version FOREIGN KEY (skill_id, version) REFERENCES qa.skill_version (skill_id, version)
        ON DELETE CASCADE,
    CONSTRAINT ck_skill_trigger_position CHECK (position BETWEEN 1 AND 10),
    CONSTRAINT ck_skill_trigger_question CHECK (char_length(question) BETWEEN 1 AND 1000)
);

-- The golden cases of a version: a question, the page that must come back for it and an optional fact of that page.
-- Approval needs 2-5 of them (service rule); the nightly check ranks each with its stored vector.
CREATE TABLE qa.skill_golden_case (
    skill_id         uuid                    NOT NULL,
    version          integer                 NOT NULL,
    position         integer                 NOT NULL,
    question         text                    NOT NULL,
    document_id      uuid                    NOT NULL,
    page             integer                 NOT NULL,
    fact             text,
    embedding        extensions.vector(1024) NOT NULL,
    embedding_model  text                    NOT NULL,
    CONSTRAINT pk_skill_golden_case PRIMARY KEY (skill_id, version, position),
    CONSTRAINT fk_skill_golden_case_version FOREIGN KEY (skill_id, version)
        REFERENCES qa.skill_version (skill_id, version) ON DELETE CASCADE,
    CONSTRAINT ck_skill_golden_case_position CHECK (position BETWEEN 1 AND 5),
    CONSTRAINT ck_skill_golden_case_question CHECK (char_length(question) BETWEEN 1 AND 1000),
    CONSTRAINT ck_skill_golden_case_page CHECK (page >= 1),
    CONSTRAINT ck_skill_golden_case_fact CHECK (fact IS NULL OR char_length(fact) BETWEEN 1 AND 200)
);

-- Who approved in which cycle (two-person rule: distinct accounts).
CREATE TABLE qa.skill_approval (
    skill_id     uuid        NOT NULL,
    cycle        integer     NOT NULL,
    approver_id  text        NOT NULL,
    approved_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_skill_approval PRIMARY KEY (skill_id, cycle, approver_id),
    CONSTRAINT fk_skill_approval_skill FOREIGN KEY (skill_id) REFERENCES qa.skill (id) ON DELETE CASCADE,
    CONSTRAINT ck_skill_approval_approver CHECK (char_length(approver_id) BETWEEN 1 AND 255)
);

-- The state history of a skill: what changed and why (who: the audit log).
CREATE TABLE qa.skill_event (
    id          uuid        NOT NULL DEFAULT uuidv7(),
    skill_id    uuid        NOT NULL,
    version     integer     NOT NULL,
    from_state  text,
    to_state    text        NOT NULL,
    reason      text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_skill_event PRIMARY KEY (id),
    CONSTRAINT fk_skill_event_skill FOREIGN KEY (skill_id) REFERENCES qa.skill (id) ON DELETE CASCADE,
    CONSTRAINT ck_skill_event_reason CHECK (reason ~ '^[A-Z_]{1,40}$')
);
CREATE INDEX idx_skill_event_skill ON qa.skill_event (skill_id, created_at, id);

-- The nightly golden-set check runs the golden cases of APPROVED skills too (ADR-0053): one row per case and run.
CREATE TABLE qa.skill_eval_result (
    run_id    uuid    NOT NULL,
    skill_id  uuid    NOT NULL,
    version   integer NOT NULL,
    position  integer NOT NULL,
    outcome   text    NOT NULL,
    rank      integer,
    CONSTRAINT pk_skill_eval_result PRIMARY KEY (run_id, skill_id, position),
    CONSTRAINT fk_skill_eval_result_run FOREIGN KEY (run_id) REFERENCES qa.eval_run (id) ON DELETE CASCADE,
    CONSTRAINT fk_skill_eval_result_skill FOREIGN KEY (skill_id) REFERENCES qa.skill (id) ON DELETE CASCADE,
    CONSTRAINT ck_skill_eval_result_outcome CHECK (outcome IN ('FOUND', 'MISSED', 'SOURCE_LOST', 'SKIPPED')),
    CONSTRAINT ck_skill_eval_result_rank CHECK (rank IS NULL OR rank >= 1)
);
CREATE INDEX idx_skill_eval_result_skill ON qa.skill_eval_result (skill_id);

-- The skill cases of a run, beside the card cases (the card recall and its alert are unchanged).
ALTER TABLE qa.eval_run
    ADD COLUMN skill_cases integer NOT NULL DEFAULT 0,
    ADD COLUMN skill_found integer NOT NULL DEFAULT 0,
    ADD COLUMN skill_missed integer NOT NULL DEFAULT 0,
    ADD COLUMN skill_source_lost integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_eval_run_skill_counts CHECK (skill_cases >= 0 AND skill_found >= 0 AND skill_missed >= 0
        AND skill_source_lost >= 0);

-- A skill going stale is a hook trigger (notify its owners, ADR-0052): one more trigger, one more subject, one rule.
ALTER TABLE qa.hook_rule DROP CONSTRAINT ck_hook_rule_trigger;
ALTER TABLE qa.hook_rule ADD CONSTRAINT ck_hook_rule_trigger CHECK (trigger_type IN ('DOCUMENT_CHANGED',
    'CLUSTER_EMERGED', 'CLUSTER_GROWING', 'CONTRADICTION_FOUND', 'CARD_STALE', 'CARD_DISPUTED', 'GOLDEN_RECALL_DROP',
    'VERIFICATION_DUE', 'SKILL_STALE'));
ALTER TABLE qa.hook_notification DROP CONSTRAINT ck_hook_notification_subject;
ALTER TABLE qa.hook_notification ADD CONSTRAINT ck_hook_notification_subject CHECK (subject_type IN ('DOCUMENT',
    'KNOWLEDGE_CARD', 'QUESTION_CLUSTER', 'CONTRADICTION', 'EVAL_RUN', 'SKILL'));
INSERT INTO qa.hook_rule (rule_key, trigger_type, action, enabled, default_enabled) VALUES
    ('SKILL_STALE_NOTIFY', 'SKILL_STALE', 'NOTIFY', true, true);

-- Versions, steps, bindings, triggers, golden cases, approvals, events and results are written once.
REVOKE UPDATE ON qa.skill_version FROM svc_qa;
REVOKE UPDATE ON qa.skill_step FROM svc_qa;
REVOKE UPDATE ON qa.skill_step_source FROM svc_qa;
REVOKE UPDATE ON qa.skill_trigger FROM svc_qa;
REVOKE UPDATE ON qa.skill_golden_case FROM svc_qa;
REVOKE UPDATE ON qa.skill_approval FROM svc_qa;
REVOKE UPDATE ON qa.skill_event FROM svc_qa;
REVOKE UPDATE ON qa.skill_eval_result FROM svc_qa;
-- A version and its provenance are deleted only with the skill (ON DELETE CASCADE runs with the owner's rights).
REVOKE DELETE ON qa.skill_version FROM svc_qa;
REVOKE DELETE ON qa.skill_step FROM svc_qa;
REVOKE DELETE ON qa.skill_step_source FROM svc_qa;
REVOKE DELETE ON qa.skill_trigger FROM svc_qa;
REVOKE DELETE ON qa.skill_golden_case FROM svc_qa;
REVOKE DELETE ON qa.skill_approval FROM svc_qa;
REVOKE DELETE ON qa.skill_event FROM svc_qa;
