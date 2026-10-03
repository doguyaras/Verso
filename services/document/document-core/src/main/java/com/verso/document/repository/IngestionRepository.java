package com.verso.document.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentFormat;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The ingestion job on document.document (ADR-0003, reference 11.1). A claim sets PROCESSING with a lease and a fresh
 * claim token; every later write names that token, so a worker whose lease expired (and whose row another worker
 * took) changes nothing. Statements only, no transactions: IngestionTransactionService owns those.
 */
@Repository
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IngestionRepository {

    /** A claimed document: what the worker needs, nothing more. */
    public record Claim(UUID documentId, String accountId, DocumentFormat format, UUID token, int attempts) {
    }

    /** One chunk ready to store: its page, position, text and vector. */
    public record StoredChunk(int pageNumber, int chunkIndex, String content, float[] embedding) {
    }

    /** The ingestion queue across all accounts (no account in it): for metrics and alerts (ADR-0014). */
    public record QueueStats(long pending, long processing, double oldestDueWaitSeconds) {
    }

    private static final int INSERT_BATCH = 100;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final JdbcTemplate statsTemplate;

    public IngestionRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
        // The metrics read runs at scrape time: 2 s at most, well inside Prometheus' 10 s scrape timeout, so a slow
        // database shows as NaN (alert VersoDatabaseUnavailable) instead of a failed scrape (VersoDown; review R3).
        this.statsTemplate = template.getDataSource() == null ? template : new JdbcTemplate(template.getDataSource());
        this.statsTemplate.setQueryTimeout(2);
    }

    /**
     * Queue size and how long the oldest due document has been waiting: a growing wait means the worker does not keep
     * up or is paused (alert IngestionBacklog). One read over the queued rows; no account, no id leaves.
     */
    public QueueStats queueStats() {
        return statsTemplate.queryForObject("""
                        SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending,
                               count(*) FILTER (WHERE status = 'PROCESSING') AS processing,
                               COALESCE(EXTRACT(EPOCH FROM now() - min(next_attempt_at)
                                   FILTER (WHERE status = 'PENDING' AND next_attempt_at <= now())), 0) AS oldest
                        FROM document.document WHERE status IN ('PENDING', 'PROCESSING')""",
                (rs, n) -> new QueueStats(rs.getLong("pending"), rs.getLong("processing"), rs.getDouble("oldest")));
    }

    /**
     * Claims one due document: PENDING whose retry time has come, or PROCESSING whose lease expired (a crashed or
     * stuck worker). SKIP LOCKED lets parallel workers take different rows without waiting (reference 11.1).
     */
    public Optional<Claim> claimNext(Instant now, Instant lockedUntil) {
        UUID token = UUID.randomUUID();
        return jdbc.sql("""
                        WITH candidate AS (
                            SELECT id FROM document.document
                            WHERE (status = 'PENDING' AND next_attempt_at <= :now)
                               OR (status = 'PROCESSING' AND locked_until <= :now)
                            ORDER BY next_attempt_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1)
                        UPDATE document.document d
                        SET status = 'PROCESSING', claim_token = :token, locked_until = :until,
                            attempts = d.attempts + 1, updated_at = :now
                        FROM candidate WHERE d.id = candidate.id
                        RETURNING d.id, d.account_id, d.format, d.attempts
                        """)
                .param("now", Timestamp.from(now)).param("until", Timestamp.from(lockedUntil)).param("token", token)
                .query((rs, n) -> new Claim(rs.getObject("id", UUID.class), rs.getString("account_id"),
                        DocumentFormat.valueOf(rs.getString("format")), token,
                        rs.getInt("attempts")))
                .optional();
    }

    /** Locks the claimed row for the rest of the transaction; false when the claim is lost (lease taken, deleted). */
    public boolean holdClaim(Claim claim, Instant now) {
        return jdbc.sql("UPDATE document.document SET updated_at = :now WHERE id = :id AND claim_token = :token")
                .param("now", Timestamp.from(now)).param("id", claim.documentId()).param("token", claim.token())
                .update() == 1;
    }

    public boolean extendLease(Claim claim, Instant now, Instant lockedUntil) {
        return jdbc.sql("UPDATE document.document SET locked_until = :until, updated_at = :now "
                        + "WHERE id = :id AND claim_token = :token")
                .param("until", Timestamp.from(lockedUntil)).param("now", Timestamp.from(now))
                .param("id", claim.documentId()).param("token", claim.token()).update() == 1;
    }

    public Optional<byte[]> loadFile(UUID documentId) {
        return jdbc.sql("SELECT content FROM document.document_file WHERE document_id = :id")
                .param("id", documentId).query((rs, n) -> rs.getBytes("content")).optional();
    }

    /** Page texts in page order; empty when the document has not been parsed yet. */
    public List<String> loadPages(UUID documentId) {
        return jdbc.sql("SELECT content FROM document.document_page WHERE document_id = :id ORDER BY page_number")
                .param("id", documentId).query((rs, n) -> rs.getString("content")).list();
    }

    /** Replaces the pages (a retried claim may have stored some) and drops the PDF: ADR-0011, kept only until parsed. */
    public void replacePagesAndDropFile(UUID documentId, List<String> pages) {
        jdbc.sql("DELETE FROM document.document_page WHERE document_id = :id").param("id", documentId).update();
        List<Object[]> rows = new ArrayList<>(pages.size());
        for (int i = 0; i < pages.size(); i++) rows.add(new Object[]{documentId, i + 1, pages.get(i)});
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
            template.batchUpdate("INSERT INTO document.document_page (document_id, page_number, content) VALUES (?, ?, ?)",
                    rows.subList(from, Math.min(rows.size(), from + INSERT_BATCH)));
        }
        jdbc.sql("DELETE FROM document.document_file WHERE document_id = :id").param("id", documentId).update();
    }

    /** Stores the chunks (replacing any of an earlier attempt) and marks the document READY. Call under holdClaim. */
    public void complete(Claim claim, int pageCount, List<StoredChunk> chunks, String model, Instant now) {
        jdbc.sql("DELETE FROM document.document_chunk WHERE document_id = :id").param("id", claim.documentId()).update();
        List<Object[]> rows = new ArrayList<>(chunks.size());
        for (StoredChunk chunk : chunks) {
            rows.add(new Object[]{claim.documentId(), claim.accountId(), chunk.pageNumber(), chunk.chunkIndex(),
                    chunk.content(), vectorLiteral(chunk.embedding()), model});
        }
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
            template.batchUpdate("INSERT INTO document.document_chunk (document_id, account_id, page_number, chunk_index, "
                            + "content, embedding, embedding_model) VALUES (?, ?, ?, ?, ?, CAST(? AS extensions.vector), ?)",
                    rows.subList(from, Math.min(rows.size(), from + INSERT_BATCH)));
        }
        jdbc.sql("""
                        UPDATE document.document
                        SET status = 'READY', page_count = :pages, chunk_count = :chunks, embedding_model = :model,
                            claim_token = NULL, locked_until = NULL, updated_at = :now
                        WHERE id = :id AND claim_token = :token
                        """)
                .param("pages", pageCount).param("chunks", chunks.size()).param("model", model)
                .param("now", Timestamp.from(now)).param("id", claim.documentId()).param("token", claim.token())
                .update();
    }

    /** Gives up: FAILED with a reason, and every derived piece of content is removed (data minimisation, ADR-0011). */
    public void fail(Claim claim, DocumentFailureReason reason, Instant now) {
        for (String table : List.of("document_chunk", "document_page", "document_file")) {
            jdbc.sql("DELETE FROM document." + table + " WHERE document_id = :id").param("id", claim.documentId()).update();
        }
        jdbc.sql("""
                        UPDATE document.document
                        SET status = 'FAILED', failure_reason = :reason, claim_token = NULL, locked_until = NULL,
                            updated_at = :now
                        WHERE id = :id AND claim_token = :token
                        """)
                .param("reason", reason.name()).param("now", Timestamp.from(now))
                .param("id", claim.documentId()).param("token", claim.token()).update();
    }

    /** Back to PENDING after a temporary failure; claimed again once next_attempt_at has come. */
    public void retryLater(Claim claim, Instant nextAttemptAt, Instant now) {
        jdbc.sql("""
                        UPDATE document.document
                        SET status = 'PENDING', next_attempt_at = :next, claim_token = NULL, locked_until = NULL,
                            updated_at = :now
                        WHERE id = :id AND claim_token = :token
                        """)
                .param("next", Timestamp.from(nextAttemptAt)).param("now", Timestamp.from(now))
                .param("id", claim.documentId()).param("token", claim.token()).update();
    }

    /**
     * Hands the document back without counting this claim as an attempt: the model was unavailable or the
     * application is shutting down (phase 4 review R1/R3). It is due again at once; the worker's pause spaces it out.
     */
    public void release(Claim claim, Instant now) {
        jdbc.sql("""
                        UPDATE document.document
                        SET status = 'PENDING', attempts = GREATEST(attempts - 1, 0), next_attempt_at = :now,
                            claim_token = NULL, locked_until = NULL, updated_at = :now
                        WHERE id = :id AND claim_token = :token
                        """)
                .param("now", Timestamp.from(now)).param("id", claim.documentId()).param("token", claim.token())
                .update();
    }

    /** pgvector's text form, "[0.1,0.2,...]"; Float.toString keeps every float exactly. */
    static String vectorLiteral(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) text.append(',');
            float value = vector[i];
            if (!Float.isFinite(value)) throw new IllegalArgumentException("embedding contains a non-finite value");
            text.append(value);
        }
        return text.append(']').toString();
    }
}
