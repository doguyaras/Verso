-- KVKK data-subject requests (ADR-0034): verso-admin lists and exports the rows in which one subject is the actor.
-- Without an index that is a full scan of up to a year of rows (ADR-0026 retention) on every request. The index holds
-- the opaque token subject, which the table already holds; it adds no new personal data and no new reader.
--
-- Rollout: CREATE INDEX blocks inserts into audit.event until it ends (Flyway runs it in a transaction, so not
-- CONCURRENTLY). The table is a year of short rows; on the measured installations that is seconds. An insert that
-- waits longer than the application role's statement_timeout is counted as a lost row and alarms
-- (verso.audit.events.not.stored, AuditEventNotStored, ADR-0026): migrate outside working hours, as for V3 of document.
-- Rollback: DROP INDEX audit.idx_event_actor_occurred (no data change).

CREATE INDEX idx_event_actor_occurred ON audit.event (actor_sub, occurred_at, id);
