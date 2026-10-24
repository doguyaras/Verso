-- Audit log of the audit module (ADR-0026). Owner: the audit module, schema "audit", migrated as svc_audit_migrate and
-- used as svc_audit (reference 10.1). Append-only: the application role may INSERT and SELECT and nothing else. Ids
-- are UUIDv7 from PostgreSQL 18 (reference 10.3). Other modules' ids (documents, conversations, turns) are plain uuid
-- columns without a foreign key (AGENTS.md section 4).
--
-- Privacy (security-rules 4/5): no column holds a question, an answer, a file name, an address, a user agent or any
-- free text. "What" is a type and an id; the few text columns are closed vocabularies enforced by CHECK constraints
-- below, so a bug in a publisher cannot smuggle content in. The actor is the opaque token subject; roles are Verso's
-- two role names (ADR-0019), never the identity provider's groups.

CREATE TABLE audit.event (
    id                     uuid        NOT NULL DEFAULT uuidv7(),
    -- The database's clock, never the application's: the application does not send this column.
    occurred_at            timestamptz NOT NULL DEFAULT now(),
    -- The validated token's sub (opaque to us; JwtValidation checks its shape).
    actor_sub              text        NOT NULL,
    actor_roles            text[]      NOT NULL DEFAULT '{}',
    -- Names of the AuditAction / AuditOutcome / AuditTargetType enums. No CHECK on the action list: a new action is a
    -- code change and must not need a migration of a table this large; the shape is checked instead.
    action                 text        NOT NULL,
    outcome                text        NOT NULL,
    target_type            text        NOT NULL,
    target_id              uuid,
    conversation_id        uuid,
    -- A fixed UPPER_SNAKE word: answer outcome, rating, or the denied endpoint.
    detail_code            text,
    -- The chat model as configured, e.g. gemma4:e2b; set by the server, not by the caller.
    model_id               text,
    citation_document_ids  uuid[]      NOT NULL DEFAULT '{}',
    -- The request's trace id (32 hex digits), to find the log lines of the same request.
    trace_id               text        NOT NULL,
    mode                   text        NOT NULL,
    CONSTRAINT pk_event PRIMARY KEY (id),
    CONSTRAINT ck_event_actor_sub CHECK (char_length(actor_sub) BETWEEN 1 AND 255),
    CONSTRAINT ck_event_actor_roles CHECK (actor_roles <@ ARRAY['verso-admin', 'verso-user']::text[]),
    CONSTRAINT ck_event_action CHECK (action ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT ck_event_outcome CHECK (outcome IN ('SUCCESS', 'DENIED')),
    CONSTRAINT ck_event_target_type CHECK (target_type ~ '^[A-Z][A-Z0-9_]{2,31}$'),
    CONSTRAINT ck_event_detail_code CHECK (detail_code IS NULL OR detail_code ~ '^[A-Z0-9_]{1,40}$'),
    CONSTRAINT ck_event_model_id CHECK (model_id IS NULL OR model_id ~ '^[A-Za-z0-9._:/@+-]{1,100}$'),
    CONSTRAINT ck_event_citations CHECK (cardinality(citation_document_ids) <= 50),
    CONSTRAINT ck_event_trace_id CHECK (trace_id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_event_mode CHECK (mode IN ('local', 'cloud'))
);

-- Not partitioned, on purpose (ADR-0026): the migration rules forbid dynamic SQL and GRANT, which runtime partition
-- creation would need, and a fixed set of partitions in a migration runs out. Volume is small (one row per audited
-- operation: a few per question, order of 10^5 per month for a busy installation) and the retention function deletes
-- in batches from this index. Reconsider when the table passes server RAM (reference 10.6, ADR-0026 re-evaluation).
--
-- GET /v1/admin/audit and the export: a time window, newest first (the export reads it forward with keyset paging).
-- Also the retention scan: rows older than the cutoff.
CREATE INDEX idx_event_occurred ON audit.event (occurred_at DESC, id DESC);
-- The same window filtered by action.
CREATE INDEX idx_event_action_occurred ON audit.event (action, occurred_at DESC, id DESC);

-- Append-only for the application role. Default privileges gave it UPDATE and DELETE on this table (20-database.sh);
-- TRUNCATE is revoked as well, so that the intent reads the same in the catalog as in this file.
REVOKE UPDATE, DELETE, TRUNCATE ON audit.event FROM svc_audit;

-- The only way for the application role to remove rows: retention. SECURITY DEFINER runs as the table owner
-- (svc_audit_migrate), so the application role needs no DELETE. Narrow by construction:
--  - only svc_audit may call it (session_user): PUBLIC can EXECUTE a new function, and verso_backup (pg_read_all_data)
--    can reach the schema, but neither passes this check;
--  - it refuses a cutoff younger than 30 days (the floor of verso.audit.retention-days), so a compromised application
--    cannot erase recent history;
--  - it deletes at most batch_size rows per call, oldest first.
-- search_path is not pinned in the function (the migration rules forbid SET search_path): every object is schema
-- qualified, and svc_audit has no CREATE right on any schema of its search_path (audit, extensions), so nothing can
-- shadow a name.
CREATE FUNCTION audit.purge_expired(cutoff timestamptz, batch_size integer)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
AS $$
DECLARE
    removed integer;
BEGIN
    IF session_user <> 'svc_audit' THEN
        RAISE EXCEPTION 'audit.purge_expired: caller not allowed' USING ERRCODE = '42501';
    END IF;
    IF cutoff IS NULL OR cutoff > pg_catalog.now() - interval '30 days' THEN
        RAISE EXCEPTION 'audit.purge_expired: cutoff must be at least 30 days old' USING ERRCODE = '22023';
    END IF;
    IF batch_size IS NULL OR batch_size < 1 OR batch_size > 5000 THEN
        RAISE EXCEPTION 'audit.purge_expired: batch_size must be 1..5000' USING ERRCODE = '22023';
    END IF;
    DELETE FROM audit.event
    WHERE id IN (SELECT e.id FROM audit.event e WHERE e.occurred_at < cutoff ORDER BY e.occurred_at LIMIT batch_size);
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END
$$;
