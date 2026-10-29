package com.verso.document.repository;

import com.verso.document.config.ConditionalOnIngestion;
import com.verso.document.repository.IngestionRepository.StoredChunk;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Re-indexing READY documents with context headers (ADR-0061, V7): the backfill's lease on document.context_locked_until,
 * what it reads, and the replacement of a document's chunks under their own ids. Statements only: the backfill job
 * owns the transactions and never holds one across an embedding call (reference 4.3).
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnIngestion
public class ContextIndexRepository {

    /** A chunk as stored: id, page, position and text. The text is never logged. */
    public record ChunkRow(UUID id, int pageNumber, int chunkIndex, String content) {

        @Override
        public String toString() {
            return "ChunkRow[id=" + id + ", chunkIndex=" + chunkIndex + "]";
        }
    }

    /** What re-indexing one document needs. File name, title, pages and chunks are document text: never logged. */
    public record Source(String fileName, String indexTitle, List<String> pages, List<ChunkRow> chunks) {

        @Override
        public String toString() {
            return "Source[pages=" + pages.size() + ", chunks=" + chunks.size() + "]";
        }
    }

    /** Any other version, older or newer: switching embed-header back and forth re-indexes too. */
    private static final String PENDING = "d.status = 'READY' AND d.embedding_model = :model "
            + "AND d.context_version IS DISTINCT FROM :version";

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public ContextIndexRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    /**
     * Takes one READY document indexed with another index version (or none) and with the running embedding model,
     * whose lease is free, and leases it until {@code until}. SKIP LOCKED: instances take different documents. A
     * document of another embedding model is not taken: that needs a real model migration (llm-rules 6.2), which a
     * background job must not start on its own after a configuration mistake.
     */
    public Optional<UUID> claimNext(int version, String model, Instant now, Instant until) {
        return jdbc.sql("""
                        WITH candidate AS (
                            SELECT d.id FROM document.document d
                            WHERE %s AND (d.context_locked_until IS NULL OR d.context_locked_until <= :now)
                            ORDER BY d.id
                            LIMIT 1
                            FOR NO KEY UPDATE OF d SKIP LOCKED)
                        UPDATE document.document d SET context_locked_until = :until
                        FROM candidate WHERE d.id = candidate.id
                        RETURNING d.id
                        """.formatted(PENDING))
                .param("model", model).param("version", version)
                .param("now", Timestamp.from(now)).param("until", Timestamp.from(until))
                .query(UUID.class).optional();
    }

    /** Keeps the lease while a large document is embedded batch by batch. */
    public void extendLease(UUID documentId, Instant until) {
        jdbc.sql("UPDATE document.document SET context_locked_until = :until WHERE id = :id")
                .param("until", Timestamp.from(until)).param("id", documentId).update();
    }

    /** Hands the document back at once (the embedding model failed); otherwise the lease would run out. */
    public void releaseLease(UUID documentId) {
        jdbc.sql("UPDATE document.document SET context_locked_until = NULL WHERE id = :id")
                .param("id", documentId).update();
    }

    /** The document's file name, stored title, pages and chunks; empty when it is gone or no longer READY. */
    public Optional<Source> load(UUID documentId) {
        Optional<String[]> document = jdbc.sql("SELECT file_name, index_title FROM document.document "
                        + "WHERE id = :id AND status = 'READY'")
                .param("id", documentId)
                .query((rs, n) -> new String[]{rs.getString("file_name"), rs.getString("index_title")})
                .optional();
        if (document.isEmpty()) return Optional.empty();
        List<String> pages = jdbc.sql("SELECT content FROM document.document_page WHERE document_id = :id "
                        + "ORDER BY page_number")
                .param("id", documentId).query(String.class).list();
        List<ChunkRow> chunks = jdbc.sql("SELECT id, page_number, chunk_index, content FROM document.document_chunk "
                        + "WHERE document_id = :id ORDER BY chunk_index")
                .param("id", documentId)
                .query((rs, n) -> new ChunkRow(rs.getObject("id", UUID.class), rs.getInt("page_number"),
                        rs.getInt("chunk_index"), rs.getString("content")))
                .list();
        return Optional.of(new Source(document.get()[0], document.get()[1], pages, chunks));
    }

    /**
     * Replaces the document's chunks with the re-indexed ones under the same ids, in the caller's transaction. Runs
     * only when, under the document's row lock, the document is still READY, still pending for this version and model,
     * and its chunks are still exactly {@code expectedIds}: a document deleted, re-ingested or finished by another
     * instance meanwhile is left alone (false). The application role cannot UPDATE chunks (V1): they are deleted and
     * inserted again, with the same id, page, position and text. The system model read from them goes too (its
     * mentions would cascade with the chunks); the caller extracts it again.
     */
    public boolean replace(UUID documentId, int version, String model, String indexTitle, List<UUID> expectedIds,
                           List<StoredChunk> chunks) {
        Optional<String> account = jdbc.sql("SELECT d.account_id FROM document.document d WHERE d.id = :id AND "
                        + PENDING + " FOR NO KEY UPDATE")
                .param("id", documentId).param("model", model).param("version", version)
                .query(String.class).optional();
        if (account.isEmpty()) return false;
        List<UUID> current = jdbc.sql("SELECT id FROM document.document_chunk WHERE document_id = :id "
                        + "ORDER BY chunk_index")
                .param("id", documentId).query(UUID.class).list();
        if (!current.equals(expectedIds) || chunks.size() != expectedIds.size()) return false;
        jdbc.sql("DELETE FROM document.system_entity WHERE document_id = :id").param("id", documentId).update();
        jdbc.sql("DELETE FROM document.system_model_state WHERE document_id = :id").param("id", documentId).update();
        jdbc.sql("DELETE FROM document.document_chunk WHERE document_id = :id").param("id", documentId).update();
        IngestionRepository.insertChunks(template, documentId, account.get(), expectedIds, chunks, model);
        jdbc.sql("""
                        UPDATE document.document
                        SET context_version = :version, index_title = :title, context_locked_until = NULL
                        WHERE id = :id
                        """)
                .param("version", version).param("title", indexTitle).param("id", documentId).update();
        return true;
    }
}
