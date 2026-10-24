-- Delivery key of the durable audit path (ADR-0039). The document and qa modules now write each audit record to an
-- outbox in their own schema, in the business transaction, and a relay copies it here. A relay can deliver the same
-- record twice (it crashed after this insert and before it deleted its outbox row, or its lease ran out): the unique
-- event_id turns the second insert into a no-op (INSERT ... ON CONFLICT (event_id) DO NOTHING), so a retry never
-- duplicates a row. svc_audit keeps INSERT and SELECT only; ON CONFLICT DO NOTHING needs nothing more.
--
-- Nullable on purpose: rows written before this version have no key, and a volatile default would rewrite the table.
-- Every row written from this version on carries one (the direct path for access denials and privacy requests draws a
-- random one). NULLs never conflict with each other.
--
-- Rollout: ADD COLUMN without a default is a catalogue change. CREATE UNIQUE INDEX blocks inserts into audit.event
-- until it ends (Flyway runs it in a transaction, so not CONCURRENTLY); same measure as V2: seconds for a year of
-- short rows, migrate outside working hours. The application of this release needs the column: migrate first, then
-- start the new image (the old image ignores the column and keeps working).
-- Rollback: DROP INDEX audit.uq_event_event_id; ALTER TABLE audit.event DROP COLUMN event_id (only with the old image).

ALTER TABLE audit.event ADD COLUMN event_id uuid;

CREATE UNIQUE INDEX uq_event_event_id ON audit.event (event_id);
