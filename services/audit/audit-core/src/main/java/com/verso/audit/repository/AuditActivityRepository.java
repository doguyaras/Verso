package com.verso.audit.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * The audit module's read for the activity centre (ADR-0058): successful document deletions per UTC day and action,
 * counts only. No actor, no target id: a deleted document has no name left to show and who deleted it stays in the
 * audit log behind its own export (ADR-0026).
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditActivityRepository {

    /** Deletions of one day by one action (DOCUMENT_DELETED by a person, CONNECTOR_DOCUMENT_DELETED by a sync). */
    public record DeletionDay(Instant day, String action, long count, Instant latest) {
    }

    private final AuditDatabase db;

    public AuditActivityRepository(AuditDatabase db) {
        this.db = db;
    }

    public List<DeletionDay> deletions(Instant from, Instant to, int limit) {
        return db.jdbc().sql("SELECT date_trunc('day', occurred_at, 'UTC') AS day, action, count(*) AS n, "
                        + "max(occurred_at) AS latest FROM audit.event WHERE action IN ('DOCUMENT_DELETED', "
                        + "'CONNECTOR_DOCUMENT_DELETED') AND outcome = 'SUCCESS' AND occurred_at >= :from "
                        + "AND occurred_at < :to GROUP BY 1, 2 ORDER BY latest DESC LIMIT :limit")
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("limit", limit)
                .query((rs, n) -> new DeletionDay(rs.getTimestamp("day").toInstant(), rs.getString("action"),
                        rs.getLong("n"), rs.getTimestamp("latest").toInstant())).list();
    }
}
