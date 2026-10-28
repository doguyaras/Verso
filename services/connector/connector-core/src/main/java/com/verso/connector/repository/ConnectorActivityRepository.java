package com.verso.connector.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * The connector module's reads for the activity centre (ADR-0058): sync runs of a window and the deletions the
 * deletion safety holds back. The connector's admin-given name, type and collection only; never the host, the share,
 * the user name or the credential reference, and never {@code created_by}.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorActivityRepository {

    /** A run with the name, type and collection of its connector. */
    public record RunRow(UUID id, UUID connectorId, String connectorName, String connectorType, UUID collectionId,
                         String status, String errorCode, Instant startedAt, Instant finishedAt, int listed, int added,
                         int updated, int deleted, int unchanged, int failed, int skipped, int deferred,
                         String listingMode, Integer pendingDeletions, boolean massDeletionConfirmed) {
    }

    private static final String COLUMNS = "r.id, r.connector_id, c.name, c.type, c.collection_id, r.status, "
            + "r.error_code, r.started_at, r.finished_at, r.listed, r.added, r.updated, r.deleted, r.unchanged, "
            + "r.failed, r.skipped, r.deferred, r.listing_mode, r.pending_deletions, c.mass_deletion_confirmed";

    private final ConnectorDatabase db;

    public ConnectorActivityRepository(ConnectorDatabase db) {
        this.db = db;
    }

    public List<RunRow> runs(Instant from, Instant to, int limit) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM connector.sync_run r JOIN connector.connector c "
                        + "ON c.id = r.connector_id WHERE r.started_at >= :from AND r.started_at < :to "
                        + "ORDER BY r.started_at DESC, r.id DESC LIMIT :limit")
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("limit", limit)
                .query(ConnectorActivityRepository::row).list();
    }

    public Optional<RunRow> run(UUID id) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM connector.sync_run r JOIN connector.connector c "
                        + "ON c.id = r.connector_id WHERE r.id = :id")
                .param("id", id).query(ConnectorActivityRepository::row).optional();
    }

    /**
     * The newest finished run of every live connector whose deletions the safety held back and that no admin has
     * confirmed yet: each is a bulk deletion waiting for a person (ADR-0038, ADR-0044).
     */
    public List<RunRow> heldDeletions(Instant before, int limit) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM connector.connector c JOIN LATERAL (SELECT * FROM "
                        + "connector.sync_run s WHERE s.connector_id = c.id AND s.finished_at IS NOT NULL "
                        + "ORDER BY s.started_at DESC, s.id DESC LIMIT 1) r ON true WHERE NOT c.deleting "
                        + "AND NOT c.mass_deletion_confirmed AND COALESCE(r.pending_deletions, 0) > 0 "
                        + "AND r.started_at < :before ORDER BY r.started_at DESC LIMIT :limit")
                .param("before", Timestamp.from(before)).param("limit", limit)
                .query(ConnectorActivityRepository::row).list();
    }

    private static RunRow row(ResultSet rs, int n) throws SQLException {
        return new RunRow(rs.getObject("id", UUID.class), rs.getObject("connector_id", UUID.class),
                rs.getString("name"), rs.getString("type"), rs.getObject("collection_id", UUID.class),
                rs.getString("status"), rs.getString("error_code"), ConnectorRepository.instant(rs, "started_at"),
                ConnectorRepository.instant(rs, "finished_at"), rs.getInt("listed"), rs.getInt("added"),
                rs.getInt("updated"), rs.getInt("deleted"), rs.getInt("unchanged"), rs.getInt("failed"),
                rs.getInt("skipped"), rs.getInt("deferred"), rs.getString("listing_mode"),
                rs.getObject("pending_deletions", Integer.class), rs.getBoolean("mass_deletion_confirmed"));
    }
}
