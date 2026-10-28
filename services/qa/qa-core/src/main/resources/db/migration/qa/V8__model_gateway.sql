-- Provider-agnostic model gateway (ADR-0056). Owner: the qa module, schema "qa" (the only module that calls a chat
-- model). A verso-admin adds model provider connections with their own API keys, routes every task to a primary model
-- and an ordered fallback chain, prices the models and sets monthly budgets; every call is counted here.
--
-- Keys (ADR-0056, ADR-0030): envelope encryption. key_ciphertext is AES-256-GCM under a per-key-version data key; the
-- data key is stored only wrapped by the installation's key-encryption key (KEK, /run/secrets/SECRET_MODEL_GATEWAY_KEK,
-- never in the database); both are bound to the context "verso:model-connection:<id>:v<key_version>". key_hint is the
-- last four characters for the panel. No plaintext key exists in any column, log or API response.
--
-- Privacy (works-council rule): usage rows carry no account id. Team rows of the report need k distinct users; that
-- count comes from model_team_reach, whose digest is an HMAC under a key derived from the KEK and is rolled per month
-- and team, so it cannot be joined to an account without the KEK. The egress log is the sent_categories column: the
-- kinds of data a call sent out (QUESTION, PASSAGES, ...), never content.
--
-- Rollout: new, empty tables plus one settings row; the old image never reads them and keeps using its built-in model.
-- Rollback: DROP TABLE qa.model_team_reach, qa.model_usage, qa.model_budget, qa.model_price, qa.model_route,
-- qa.model_gateway_setting, qa.model_connection.

CREATE TABLE qa.model_connection (
    id                uuid        NOT NULL DEFAULT uuidv7(),
    name              text        NOT NULL,
    provider_type     text        NOT NULL,
    base_url          text,
    region            text,
    deployment        text,
    project           text,
    models            text[]      NOT NULL,
    enabled           boolean     NOT NULL DEFAULT true,
    -- LOCAL: the host is on the internal network (a self-hostable type only); EXTERNAL: data leaves the installation.
    locality          text        NOT NULL,
    egress_host       text,
    key_ciphertext    bytea,
    key_wrapped_dek   bytea,
    key_kek_id        text,
    key_hint          text,
    key_version       integer     NOT NULL DEFAULT 0,
    key_rotated_at    timestamptz,
    validated_at      timestamptz,
    validation_status text,
    -- The token sub of the admin who added it (accountability, like cards and skills).
    created_by        text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    -- Bumped by every change: compare-and-set for concurrent admins, cache key of the built clients.
    revision          bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_model_connection PRIMARY KEY (id),
    CONSTRAINT ck_model_connection_name CHECK (char_length(name) BETWEEN 1 AND 80),
    CONSTRAINT ck_model_connection_type CHECK (provider_type IN ('OPENAI', 'AZURE_OPENAI', 'ANTHROPIC', 'BEDROCK',
        'GOOGLE_GENAI', 'MISTRAL', 'OLLAMA', 'OPENAI_COMPATIBLE')),
    CONSTRAINT ck_model_connection_locality CHECK (locality IN ('LOCAL', 'EXTERNAL')),
    -- Only the two self-hostable types can be LOCAL.
    CONSTRAINT ck_model_connection_local_type CHECK
        (locality = 'EXTERNAL' OR provider_type IN ('OLLAMA', 'OPENAI_COMPATIBLE')),
    CONSTRAINT ck_model_connection_models CHECK (cardinality(models) BETWEEN 1 AND 20),
    -- A key is all three parts or none.
    CONSTRAINT ck_model_connection_key CHECK ((key_ciphertext IS NULL) = (key_wrapped_dek IS NULL)
        AND (key_ciphertext IS NULL) = (key_kek_id IS NULL)),
    CONSTRAINT ck_model_connection_status CHECK (validation_status IS NULL OR validation_status IN
        ('OK', 'AUTH_FAILED', 'UNREACHABLE', 'MODEL_NOT_FOUND', 'RATE_LIMITED', 'FAILED')),
    CONSTRAINT ck_model_connection_creator CHECK (char_length(created_by) BETWEEN 1 AND 256)
);
CREATE UNIQUE INDEX uq_model_connection_name ON qa.model_connection (lower(name));

-- A task's route: position 0 is the primary, then the fallbacks. connection_id NULL is the built-in model.
CREATE TABLE qa.model_route (
    task          text     NOT NULL,
    position      smallint NOT NULL,
    connection_id uuid,
    model         text     NOT NULL,
    CONSTRAINT pk_model_route PRIMARY KEY (task, position),
    -- RESTRICT: a connection in a route cannot be deleted (409 MODEL_CONNECTION_IN_USE).
    CONSTRAINT fk_model_route_connection FOREIGN KEY (connection_id) REFERENCES qa.model_connection (id)
        ON DELETE RESTRICT,
    -- Embeddings stay local and pinned (llm-rules 1.2, 6.2): no route for them.
    CONSTRAINT ck_model_route_task CHECK (task IN ('ANSWER', 'FOLLOW_UP', 'DECOMPOSITION', 'SKILL_CONDENSE',
        'SKILL_RUN')),
    CONSTRAINT ck_model_route_position CHECK (position BETWEEN 0 AND 4)
);
CREATE INDEX idx_model_route_connection ON qa.model_route (connection_id);

-- One row: the gateway's own switches.
CREATE TABLE qa.model_gateway_setting (
    id                smallint    NOT NULL,
    user_model_choice boolean     NOT NULL DEFAULT false,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_model_gateway_setting PRIMARY KEY (id),
    CONSTRAINT ck_model_gateway_setting_single CHECK (id = 1)
);
INSERT INTO qa.model_gateway_setting (id) VALUES (1);

-- The admin's prices per million tokens.
CREATE TABLE qa.model_price (
    connection_id      uuid          NOT NULL,
    model              text          NOT NULL,
    input_per_million  numeric(12,4) NOT NULL,
    output_per_million numeric(12,4) NOT NULL,
    currency           text          NOT NULL,
    CONSTRAINT pk_model_price PRIMARY KEY (connection_id, model),
    CONSTRAINT fk_model_price_connection FOREIGN KEY (connection_id) REFERENCES qa.model_connection (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_model_price_amounts CHECK (input_per_million >= 0 AND output_per_million >= 0),
    CONSTRAINT ck_model_price_currency CHECK (currency ~ '^[A-Z]{3}$')
);

-- Monthly budgets of a connection (scope_key = its id) or a team (scope_key = a token group name).
CREATE TABLE qa.model_budget (
    scope               text          NOT NULL,
    scope_key           text          NOT NULL,
    hard_limit          numeric(12,2) NOT NULL,
    soft_limit          numeric(12,2),
    -- The month (YYYY-MM) whose soft / hard crossing was already reported: one notification per month.
    soft_notified_month text,
    hard_notified_month text,
    CONSTRAINT pk_model_budget PRIMARY KEY (scope, scope_key),
    CONSTRAINT ck_model_budget_scope CHECK (scope IN ('CONNECTION', 'TEAM')),
    CONSTRAINT ck_model_budget_key CHECK (char_length(scope_key) BETWEEN 1 AND 200),
    CONSTRAINT ck_model_budget_amounts CHECK (hard_limit >= 0 AND (soft_limit IS NULL OR soft_limit >= 0))
);

-- One row per model call: tokens, cost and the egress log. No account id. connection_id has no foreign key: usage
-- outlives a deleted connection (the report then says "silinmiş bağlantı").
CREATE TABLE qa.model_usage (
    id                bigint GENERATED ALWAYS AS IDENTITY,
    occurred_at       timestamptz   NOT NULL DEFAULT now(),
    month             text          NOT NULL,
    connection_id     uuid,
    model             text          NOT NULL,
    task              text          NOT NULL,
    -- The skill library's routing word of a SKILL_RUN call (skill:<skill id>:<step key>, ADR-0057): fixed words from
    -- the skill definition, never user text.
    task_detail       text,
    team              text,
    external          boolean       NOT NULL,
    outcome           text          NOT NULL,
    prompt_tokens     integer       NOT NULL DEFAULT 0,
    completion_tokens integer       NOT NULL DEFAULT 0,
    cost              numeric(14,6) NOT NULL DEFAULT 0,
    -- What kinds of data the call sent out of the installation (empty for a local call); never content.
    sent_categories   text[]        NOT NULL DEFAULT '{}',
    -- The privacy filter chain that saw the call (PASS_THROUGH until pseudonymisation lands).
    filter_policy     text          NOT NULL,
    CONSTRAINT pk_model_usage PRIMARY KEY (id),
    CONSTRAINT ck_model_usage_month CHECK (month ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT ck_model_usage_task CHECK (task IN ('ANSWER', 'FOLLOW_UP', 'DECOMPOSITION', 'SKILL_CONDENSE',
        'SKILL_RUN', 'TEST')),
    CONSTRAINT ck_model_usage_task_detail CHECK (task_detail IS NULL OR task_detail ~ '^skill:[A-Za-z0-9:_.-]{1,190}$'),
    CONSTRAINT ck_model_usage_outcome CHECK (outcome IN ('OK', 'FAILED', 'BLOCKED')),
    CONSTRAINT ck_model_usage_tokens CHECK (prompt_tokens >= 0 AND completion_tokens >= 0 AND cost >= 0)
);
-- The report and the budget sums: one month, by connection or by team.
CREATE INDEX idx_model_usage_month_connection ON qa.model_usage (month, connection_id);
CREATE INDEX idx_model_usage_month_team ON qa.model_usage (month, team) WHERE team IS NOT NULL;
-- The retention delete.
CREATE INDEX idx_model_usage_occurred ON qa.model_usage (occurred_at);

-- Distinct users per team and month, as keyed digests only (k-anonymity of the team rows).
CREATE TABLE qa.model_team_reach (
    month  text  NOT NULL,
    team   text  NOT NULL,
    digest bytea NOT NULL,
    CONSTRAINT pk_model_team_reach PRIMARY KEY (month, team, digest),
    CONSTRAINT ck_model_team_reach_month CHECK (month ~ '^[0-9]{4}-[0-9]{2}$')
);
