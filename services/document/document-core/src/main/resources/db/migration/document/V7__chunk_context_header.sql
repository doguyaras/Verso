-- Contextual chunk headers (ADR-0061): each chunk is indexed together with a short header, the document's title and
-- the section or article it sits in, so that the "müdür üç yıl" chunks of ninety near-identical regulations are no
-- longer indistinguishable. The header is index input only: it goes into the embedding input and into its own
-- full-text vector; document_chunk.content, every excerpt, citation, prompt and API response stay the chunk's own text.
--
-- Expand only. Every new column is nullable without a default (a catalogue change, no rewrite); NULL means "indexed
-- before ADR-0061". search_vector (V3) and the HNSW index are not touched: a generated column with another expression
-- would rewrite document_chunk and rebuild the HNSW index (3.5 min per 100 thousand chunks, ADR-0022). The header gets a
-- plain tsvector column instead, which the application writes with the same configuration in the same INSERT, and a
-- CHECK holds the two together. Retrieval matches search_vector OR header_vector (RetrievalRepository).
--
-- Re-indexing: the application role still cannot UPDATE chunks (V1). The backfill job (ContextHeaderBackfillJob)
-- re-embeds a READY document with another context_version and replaces its chunks in one transaction, deleting and
-- inserting them under the SAME ids (the qa module keeps chunk ids of cards, skills and suggestions as plain uuids).
-- context_locked_until is its lease, so instances share the work without embedding one document twice.
--
-- Rollout: the old image never reads the new columns; a chunk it inserts has no header and its document a NULL
-- version, which the backfill picks up later. Rollback: the old image works on this schema as it is; dropping the
-- columns is a later contract migration.

ALTER TABLE document.document
    -- The title the document's chunks are indexed under (DocumentTitle): kept because the PDF metadata title is gone
    -- with the file (ADR-0011). Index input only: never shown, never logged (llm-rules 2.1).
    ADD COLUMN index_title text,
    -- ChunkContext.indexVersion of the stored headers (rules version, header embedded or not); NULL = before ADR-0061.
    ADD COLUMN context_version integer,
    -- Backfill lease: another instance does not take the document before this time.
    ADD COLUMN context_locked_until timestamptz;

ALTER TABLE document.document
    ADD CONSTRAINT ck_document_index_title CHECK (index_title IS NULL OR char_length(index_title) BETWEEN 1 AND 200),
    ADD CONSTRAINT ck_document_context_version CHECK (context_version IS NULL OR context_version >= 1);

ALTER TABLE document.document_chunk
    -- "<title>" or "<title>\n<section>", at most 200 characters (ChunkContext.MAX_HEADER_CHARS). Document text: never
    -- logged, never returned by any read of a chunk.
    ADD COLUMN context_header text,
    -- to_tsvector('turkish', context_header): the configuration of search_vector (V3, RetrievalRepository).
    ADD COLUMN header_vector tsvector;

-- Locks: the two constraints and the index each read document_chunk once (every row NULL, nothing to compute or to
-- index) under a lock that blocks writes. Like V3 this runs in the one-shot migrate step before the application
-- starts, so no request waits; the scans are sequential reads, far shorter than V3's rewrite.
ALTER TABLE document.document_chunk
    ADD CONSTRAINT ck_document_chunk_context_header
        CHECK (context_header IS NULL OR char_length(context_header) BETWEEN 1 AND 200),
    ADD CONSTRAINT ck_document_chunk_header_vector
        CHECK (header_vector IS NOT DISTINCT FROM to_tsvector('turkish', context_header));

-- The lexical candidates of the hybrid query match search_vector OR header_vector; each side has its GIN index.
CREATE INDEX idx_document_chunk_header_vector ON document.document_chunk USING gin (header_vector);
