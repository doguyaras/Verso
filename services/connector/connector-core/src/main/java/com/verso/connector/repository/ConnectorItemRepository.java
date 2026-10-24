package com.verso.connector.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * connector.connector_item (ADR-0038): one row per tracked file. Every write is a single autocommit statement made
 * after the document module's own transaction returned, keyed by (connector, path): repeating it is harmless, which is
 * what makes a run resumable after a crash between the two (the idempotency key covers the document side).
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorItemRepository {

    /** One tracked file. */
    public record ItemRow(String path, long fileId, long sizeBytes, Instant modifiedAt, String contentSha256,
                          UUID documentId, String state, String failureCode, int attempts, Instant nextAttemptAt,
                          Instant updatedAt) {
        /** Never the path (security-rules 4: file names are not logged). */
        @Override
        public String toString() {
            return "ItemRow[state=" + state + ", documentId=" + documentId + "]";
        }
    }

    /** Counts per state. */
    public record Counts(long synced, long failed, long skipped) {
    }

    private static final String COLUMNS = "path, file_id, size_bytes, modified_at, content_sha256, document_id, state, "
            + "failure_code, attempts, next_attempt_at, updated_at";

    private static final RowMapper<ItemRow> ROW = (rs, n) -> new ItemRow(rs.getString("path"), rs.getLong("file_id"),
            rs.getLong("size_bytes"), ConnectorRepository.instant(rs, "modified_at"), rs.getString("content_sha256"),
            rs.getObject("document_id", UUID.class), rs.getString("state"), rs.getString("failure_code"),
            rs.getInt("attempts"), ConnectorRepository.instant(rs, "next_attempt_at"),
            ConnectorRepository.instant(rs, "updated_at"));

    private final ConnectorDatabase db;

    public ConnectorItemRepository(ConnectorDatabase db) {
        this.db = db;
    }

    /** Every tracked file of the connector (bounded by max-listed-entries, ADR-0038). */
    public List<ItemRow> all(UUID connectorId) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM connector.connector_item WHERE connector_id = :id ORDER BY path")
                .param("id", connectorId).query(ROW).list();
    }

    /** The current version is a document of the collection. */
    public void synced(UUID connectorId, String path, long fileId, long size, Instant modified, String sha256,
                       UUID documentId, Instant now) {
        upsert(connectorId, path, fileId, size, modified, sha256, documentId, "SYNCED", null, 0, null, now);
    }

    /**
     * The attempt failed; the previous version's document (if any) and hash stay, so readers keep the last good content
     * and an identical retry is recognised.
     */
    public void failed(UUID connectorId, String path, long fileId, long size, Instant modified, String keptSha256,
                       UUID keptDocumentId, String code, int attempts, Instant nextAttemptAt, Instant now) {
        upsert(connectorId, path, fileId, size, modified, keptSha256, keptDocumentId, "FAILED", code, attempts,
                nextAttemptAt, now);
    }

    /** Not ingestible as it is; tried again only when its fingerprint changes. */
    public void skipped(UUID connectorId, String path, long fileId, long size, Instant modified, String code,
                        Instant now) {
        upsert(connectorId, path, fileId, size, modified, null, null, "SKIPPED", code, 0, null, now);
    }

    public void delete(UUID connectorId, String path) {
        db.jdbc().sql("DELETE FROM connector.connector_item WHERE connector_id = :id AND path = :path")
                .param("id", connectorId).param("path", path).update();
    }

    public Counts counts(UUID connectorId) {
        return db.jdbc().sql("SELECT count(*) FILTER (WHERE state = 'SYNCED') AS synced, "
                        + "count(*) FILTER (WHERE state = 'FAILED') AS failed, "
                        + "count(*) FILTER (WHERE state = 'SKIPPED') AS skipped "
                        + "FROM connector.connector_item WHERE connector_id = :id")
                .param("id", connectorId)
                .query((rs, n) -> new Counts(rs.getLong("synced"), rs.getLong("failed"), rs.getLong("skipped"))).single();
    }

    /** Counts of every connector in one statement (metrics snapshot). */
    public java.util.Map<UUID, Counts> countsByConnector() {
        java.util.Map<UUID, Counts> out = new java.util.HashMap<>();
        db.jdbc().sql("SELECT connector_id, count(*) FILTER (WHERE state = 'SYNCED') AS synced, "
                        + "count(*) FILTER (WHERE state = 'FAILED') AS failed, "
                        + "count(*) FILTER (WHERE state = 'SKIPPED') AS skipped "
                        + "FROM connector.connector_item GROUP BY connector_id")
                .query((rs, n) -> out.put(rs.getObject("connector_id", UUID.class),
                        new Counts(rs.getLong("synced"), rs.getLong("failed"), rs.getLong("skipped")))).list();
        return out;
    }

    /** The admin's view of one state (or all), by path. */
    public List<ItemRow> list(UUID connectorId, String state, int page, int size) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM connector.connector_item WHERE connector_id = :id "
                        + "AND (CAST(:state AS text) IS NULL OR state = :state) ORDER BY path LIMIT :size OFFSET :offset")
                .param("id", connectorId).param("state", state).param("size", size).param("offset", (long) page * size)
                .query(ROW).list();
    }

    public long count(UUID connectorId, String state) {
        return db.jdbc().sql("SELECT count(*) FROM connector.connector_item WHERE connector_id = :id "
                        + "AND (CAST(:state AS text) IS NULL OR state = :state)")
                .param("id", connectorId).param("state", state).query(Long.class).single();
    }

    /** A batch of tracked files that still hold a document (a deleted connector's purge). */
    public List<ItemRow> withDocument(UUID connectorId, int limit) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM connector.connector_item WHERE connector_id = :id "
                        + "AND document_id IS NOT NULL ORDER BY path LIMIT :limit")
                .param("id", connectorId).param("limit", limit).query(ROW).list();
    }

    private void upsert(UUID connectorId, String path, long fileId, long size, Instant modified, String sha256,
                        UUID documentId, String state, String code, int attempts, Instant nextAttemptAt, Instant now) {
        db.jdbc().sql("INSERT INTO connector.connector_item (connector_id, path, file_id, size_bytes, modified_at, "
                        + "content_sha256, document_id, state, failure_code, attempts, next_attempt_at, updated_at) "
                        + "VALUES (:id, :path, :fileId, :size, :modified, :sha, :document, :state, :code, :attempts, "
                        + ":next, :now) ON CONFLICT (connector_id, path) DO UPDATE SET file_id = EXCLUDED.file_id, "
                        + "size_bytes = EXCLUDED.size_bytes, modified_at = EXCLUDED.modified_at, "
                        + "content_sha256 = EXCLUDED.content_sha256, document_id = EXCLUDED.document_id, "
                        + "state = EXCLUDED.state, failure_code = EXCLUDED.failure_code, attempts = EXCLUDED.attempts, "
                        + "next_attempt_at = EXCLUDED.next_attempt_at, updated_at = EXCLUDED.updated_at")
                .param("id", connectorId).param("path", path).param("fileId", fileId).param("size", size)
                .param("modified", Timestamp.from(modified)).param("sha", sha256).param("document", documentId)
                .param("state", state).param("code", code).param("attempts", attempts)
                .param("next", nextAttemptAt == null ? null : Timestamp.from(nextAttemptAt))
                .param("now", Timestamp.from(now)).update();
    }
}
