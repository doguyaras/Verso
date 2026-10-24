package com.verso.connector.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** connector.sync_run (ADR-0038): one row per run, counts and a fixed error code only. */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SyncRunRepository {

    /** A run as stored. */
    public record RunRow(UUID id, java.time.Instant startedAt, java.time.Instant finishedAt, String status,
                         String errorCode, boolean listingComplete, int listed, int added, int updated, int deleted,
                         int unchanged, int failed, int skipped, int deferred) {
    }

    /** What a run counted. */
    public record Tally(boolean listingComplete, int listed, int added, int updated, int deleted, int unchanged,
                        int failed, int skipped, int deferred) {
    }

    private static final RowMapper<RunRow> ROW = (rs, n) -> new RunRow(rs.getObject("id", UUID.class),
            ConnectorRepository.instant(rs, "started_at"), ConnectorRepository.instant(rs, "finished_at"),
            rs.getString("status"), rs.getString("error_code"), rs.getBoolean("listing_complete"), rs.getInt("listed"),
            rs.getInt("added"), rs.getInt("updated"), rs.getInt("deleted"), rs.getInt("unchanged"), rs.getInt("failed"),
            rs.getInt("skipped"), rs.getInt("deferred"));

    private final ConnectorDatabase db;

    public SyncRunRepository(ConnectorDatabase db) {
        this.db = db;
    }

    /**
     * Closes the runs a stopped instance left RUNNING (its lease expired and this claim took over), then opens a new
     * one. Returns the new run's id and its start time from the database clock.
     */
    public RunRow start(UUID connectorId) {
        db.jdbc().sql("UPDATE connector.sync_run SET status = 'ABANDONED', finished_at = now(), "
                        + "error_code = 'INSTANCE_STOPPED' WHERE connector_id = :id AND status = 'RUNNING'")
                .param("id", connectorId).update();
        return db.jdbc().sql("INSERT INTO connector.sync_run (connector_id, status) VALUES (:id, 'RUNNING') "
                        + "RETURNING id, started_at, finished_at, status, error_code, listing_complete, listed, added, "
                        + "updated, deleted, unchanged, failed, skipped, deferred")
                .param("id", connectorId).query(ROW).single();
    }

    public void finish(UUID runId, String status, String errorCode, Tally t) {
        db.jdbc().sql("UPDATE connector.sync_run SET finished_at = now(), status = :status, error_code = :error, "
                        + "listing_complete = :complete, listed = :listed, added = :added, updated = :updated, "
                        + "deleted = :deleted, unchanged = :unchanged, failed = :failed, skipped = :skipped, "
                        + "deferred = :deferred WHERE id = :id AND status = 'RUNNING'")
                .param("id", runId).param("status", status).param("error", errorCode)
                .param("complete", t.listingComplete()).param("listed", t.listed()).param("added", t.added())
                .param("updated", t.updated()).param("deleted", t.deleted()).param("unchanged", t.unchanged())
                .param("failed", t.failed()).param("skipped", t.skipped()).param("deferred", t.deferred()).update();
    }

    /** Keeps the newest {@code keep} runs of the connector. */
    public void trim(UUID connectorId, int keep) {
        db.jdbc().sql("DELETE FROM connector.sync_run WHERE connector_id = :id AND id NOT IN (SELECT id FROM "
                        + "connector.sync_run WHERE connector_id = :id ORDER BY started_at DESC, id DESC LIMIT :keep)")
                .param("id", connectorId).param("keep", keep).update();
    }

    public List<RunRow> list(UUID connectorId, int page, int size) {
        return db.jdbc().sql("SELECT id, started_at, finished_at, status, error_code, listing_complete, listed, added, "
                        + "updated, deleted, unchanged, failed, skipped, deferred FROM connector.sync_run "
                        + "WHERE connector_id = :id ORDER BY started_at DESC, id DESC LIMIT :size OFFSET :offset")
                .param("id", connectorId).param("size", size).param("offset", (long) page * size).query(ROW).list();
    }

    public long count(UUID connectorId) {
        return db.jdbc().sql("SELECT count(*) FROM connector.sync_run WHERE connector_id = :id").param("id", connectorId)
                .query(Long.class).single();
    }
}
