-- Hybrid retrieval (ADR-0022): a lexical index next to the vector index, so a question that names a code or an
-- abbreviation ("FEFO", "SAT-E027") finds the passage that contains the word even when the embedding misses it.
-- Generated from the stored chunk text: existing rows are filled by this migration, new rows by every insert, and the
-- embeddings stay as they are (no re-indexing). The text search configuration is fixed here and must be the one the
-- retrieval query uses (RetrievalRepository.TEXT_SEARCH_CONFIG); the two-argument to_tsvector is immutable, which a
-- generated column requires.
--
-- Rollout: ADD COLUMN ... STORED rewrites document_chunk and CREATE INDEX blocks writes until it ends. Both run in the
-- one-shot migrate step before the application starts (compose "migrate"), so no request waits on the lock; the
-- duration grows with the number of chunks (measured in docs/evidence/hibrit-arama-dogrulama.md).
ALTER TABLE document.document_chunk
    ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (to_tsvector('turkish', content)) STORED;

-- Lexical candidates of the hybrid query: search_vector @@ question terms, the account filter applied with it.
CREATE INDEX idx_document_chunk_search_vector ON document.document_chunk USING gin (search_vector);
