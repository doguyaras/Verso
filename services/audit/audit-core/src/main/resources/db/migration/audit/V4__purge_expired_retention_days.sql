-- Retention cutoff computed by the database (ADR-0026 follow-up, bug hunt 2026-10-30). V1's audit.purge_expired took
-- the cutoff as a timestamp the application computed from its own clock and refused one younger than
-- now() - 30 days by the database clock. At the documented floor (verso.audit.retention-days=30) the two clocks
-- compete: a JVM clock ahead of the database's by more than the call's latency (NTP drift between hosts is enough)
-- made every batch fail with 22023 and nothing was ever deleted. The function now takes the retention in days and
-- derives the cutoff from pg_catalog.now() itself, so the floor is checked and applied on one clock.
--
-- The guard stays as narrow as V1's: only svc_audit by session_user, never fewer than 30 days, at most 5000 rows per
-- call, oldest first. SECURITY DEFINER as before (the application role has no DELETE on audit.event); search_path is
-- not pinned (migration rules), every object is schema qualified.
--
-- contract: the old signature purge_expired(timestamptz, integer) is dropped in the same migration. Its only caller is
-- AuditEventRepository.purgeExpired of this release; the previous image's retention job would fail its batches
-- (function does not exist) until the new image runs, which is a skipped retention round, never a wrong delete.
-- Rollback: recreate the V1 function (this file's inverse) only together with the previous image.
-- Locks: CREATE/DROP FUNCTION take a brief lock on the function's catalog row only; audit.event is not touched.

DROP FUNCTION IF EXISTS audit.purge_expired(timestamptz, integer);

CREATE FUNCTION audit.purge_expired(retention_days integer, batch_size integer)
    RETURNS integer
    LANGUAGE plpgsql
    SECURITY DEFINER
AS $$
DECLARE
    cutoff  timestamptz;
    removed integer;
BEGIN
    IF session_user <> 'svc_audit' THEN
        RAISE EXCEPTION 'audit.purge_expired: caller not allowed' USING ERRCODE = '42501';
    END IF;
    IF retention_days IS NULL OR retention_days < 30 THEN
        RAISE EXCEPTION 'audit.purge_expired: retention_days must be at least 30' USING ERRCODE = '22023';
    END IF;
    IF batch_size IS NULL OR batch_size < 1 OR batch_size > 5000 THEN
        RAISE EXCEPTION 'audit.purge_expired: batch_size must be 1..5000' USING ERRCODE = '22023';
    END IF;
    cutoff := pg_catalog.now() - make_interval(days => retention_days);
    DELETE FROM audit.event
    WHERE id IN (SELECT e.id FROM audit.event e WHERE e.occurred_at < cutoff ORDER BY e.occurred_at LIMIT batch_size);
    GET DIAGNOSTICS removed = ROW_COUNT;
    RETURN removed;
END
$$;
