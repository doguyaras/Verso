package com.verso.qa.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * The owner's rating of a turn (ADR-0023): one row per turn, replaced by a new rating. Ownership is checked on the
 * turn before any statement here runs (TurnRepository.findOwned).
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FeedbackRepository {

    public record FeedbackRow(String rating, String reason, Integer citationNumber, Instant updatedAt) {
    }

    private final QaDatabase db;

    public FeedbackRepository(QaDatabase db) {
        this.db = db;
    }

    /**
     * Serialises the ratings of one turn until the transaction ends: the old rating is read and the rollup moved in the
     * same transaction, so two concurrent ratings cannot both count (reference 4.3, check-then-act on a row that may
     * not exist yet: advisory lock).
     */
    public void lockTurn(UUID turnId) {
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "qa.turn_feedback:" + turnId).query((rs, n) -> 1).single();
    }

    public Optional<FeedbackRow> find(UUID turnId) {
        return db.jdbc().sql("SELECT rating, reason, citation_number, updated_at FROM qa.turn_feedback WHERE turn_id = :turn")
                .param("turn", turnId)
                .query((rs, n) -> new FeedbackRow(rs.getString("rating"), rs.getString("reason"),
                        (Integer) rs.getObject("citation_number"), rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    /** Stores the rating, replacing an earlier one (idempotent per turn). */
    public FeedbackRow upsert(UUID turnId, String rating, String reason, Integer citationNumber, Instant now) {
        return db.jdbc().sql("INSERT INTO qa.turn_feedback (turn_id, rating, reason, citation_number, created_at, updated_at) "
                        + "VALUES (:turn, :rating, :reason, :citation, :now, :now) ON CONFLICT (turn_id) DO UPDATE SET "
                        + "rating = EXCLUDED.rating, reason = EXCLUDED.reason, citation_number = EXCLUDED.citation_number, "
                        + "updated_at = EXCLUDED.updated_at RETURNING rating, reason, citation_number, updated_at")
                .param("turn", turnId).param("rating", rating).param("reason", reason).param("citation", citationNumber)
                .param("now", Timestamp.from(now))
                .query((rs, n) -> new FeedbackRow(rs.getString("rating"), rs.getString("reason"),
                        (Integer) rs.getObject("citation_number"), rs.getTimestamp("updated_at").toInstant()))
                .single();
    }
}
