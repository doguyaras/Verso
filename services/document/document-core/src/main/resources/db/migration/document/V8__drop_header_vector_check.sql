-- ADR-0061 follow-up (review finding, 2026-10-30): V7's ck_document_chunk_header_vector compared header_vector with
-- to_tsvector('turkish', context_header). That output depends on the server's Snowball dictionary: pg_restore checks
-- every constraint as it adds it, so a dump restored on a PostgreSQL major version whose Turkish stemmer changed would
-- fail on every chunk with a header (restore drill, upgrade path). The invariant stays where it is made:
-- ContextIndexRepository and insertChunks build both columns from one parameter, in one statement.
--
-- contract: none in practice. It only removes a check; every row the previous release writes still satisfies the
-- schema, and the previous release never reads the constraint. Rolling back to the previous image needs no change.
-- Locks: dropping a check constraint takes a brief ACCESS EXCLUSIVE lock, with no scan.
ALTER TABLE document.document_chunk DROP CONSTRAINT IF EXISTS ck_document_chunk_header_vector;
