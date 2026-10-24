package com.verso.qa.repository;

import com.verso.qa.api.dto.CitationUnit;
import com.verso.qa.api.dto.FeedbackRating;
import com.verso.qa.api.dto.FeedbackReason;
import com.verso.qa.api.dto.SubjectHistoryCounts;
import com.verso.qa.api.dto.SubjectTurn.SubjectCitation;
import com.verso.qa.api.dto.TurnFeedback;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * SQL of KVKK data-subject requests in the "qa" schema (ADR-0034), on the module's own QaDatabase. A subject's rows are
 * those with {@code account_id = sub}: conversations, turns (with their citations and the subject's feedback, which
 * hang off the turn) and activity days. The anonymous daily totals carry no account and are not touched.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SubjectHistoryRepository {

    public record ConversationHead(UUID id, String title, Instant createdAt, Instant updatedAt) {
        @Override
        public String toString() {
            return "ConversationHead[id=" + id + "]";
        }
    }

    public record TurnRow(UUID id, UUID conversationId, Instant createdAt, String question, String answer, String outcome,
                          String mode, String model, TurnFeedback feedback) {
        @Override
        public String toString() {
            return "TurnRow[id=" + id + "]";
        }
    }

    public record CitationRow(UUID turnId, SubjectCitation citation) {
    }

    private final QaDatabase db;

    public SubjectHistoryRepository(QaDatabase db) {
        this.db = db;
    }

    public SubjectHistoryCounts count(String sub) {
        return db.jdbc().sql("""
                        SELECT (SELECT count(*) FROM qa.conversation WHERE account_id = :sub) AS conversations,
                               (SELECT count(*) FROM qa.turn WHERE account_id = :sub) AS turns,
                               (SELECT count(*) FROM qa.turn_feedback f JOIN qa.turn t ON t.id = f.turn_id
                                 WHERE t.account_id = :sub) AS feedback,
                               (SELECT count(*) FROM qa.active_account_day WHERE account_id = :sub) AS active_days""")
                .param("sub", sub)
                .query((rs, n) -> new SubjectHistoryCounts(rs.getLong("conversations"), rs.getLong("turns"),
                        rs.getLong("feedback"), rs.getLong("active_days")))
                .single();
    }

    /** Keyset by id (uuidv7, so roughly oldest first); {@code after} null starts at the beginning. */
    public List<ConversationHead> conversations(String sub, UUID after, int limit) {
        var spec = db.jdbc().sql("SELECT id, title, created_at, updated_at FROM qa.conversation WHERE account_id = :sub"
                        + (after == null ? "" : " AND id > :after") + " ORDER BY id LIMIT :limit")
                .param("sub", sub).param("limit", limit);
        if (after != null) spec = spec.param("after", after);
        return spec.query((rs, n) -> new ConversationHead(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    /** The subject's turns of these conversations with the subject's ratings, in conversation order. */
    public List<TurnRow> turns(String sub, Collection<UUID> conversationIds) {
        return db.jdbc().sql("SELECT t.id, t.conversation_id, t.created_at, t.question, t.answer, t.outcome, t.mode, "
                        + "t.chat_model, f.rating, f.reason, f.citation_number, f.updated_at AS rated_at FROM qa.turn t "
                        + "LEFT JOIN qa.turn_feedback f ON f.turn_id = t.id "
                        + "WHERE t.account_id = :sub AND t.conversation_id = ANY(:conversations) "
                        + "ORDER BY t.conversation_id, t.created_at, t.id")
                .param("sub", sub).param("conversations", conversationIds.toArray(UUID[]::new))
                .query((rs, n) -> {
                    String rating = rs.getString("rating");
                    String reason = rs.getString("reason");
                    Timestamp ratedAt = rs.getTimestamp("rated_at");
                    TurnFeedback feedback = rating == null ? null : new TurnFeedback(FeedbackRating.valueOf(rating),
                            reason == null ? null : FeedbackReason.valueOf(reason),
                            (Integer) rs.getObject("citation_number"), ratedAt.toInstant());
                    return new TurnRow(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                            rs.getTimestamp("created_at").toInstant(), rs.getString("question"), rs.getString("answer"),
                            rs.getString("outcome"), rs.getString("mode"), rs.getString("chat_model"), feedback);
                })
                .list();
    }

    public List<CitationRow> citations(String sub, Collection<UUID> conversationIds) {
        return db.jdbc().sql("SELECT c.turn_id, c.citation_number, c.document_id, c.page, c.unit FROM qa.turn_citation c "
                        + "JOIN qa.turn t ON t.id = c.turn_id "
                        + "WHERE t.account_id = :sub AND t.conversation_id = ANY(:conversations) "
                        + "ORDER BY c.turn_id, c.citation_number")
                .param("sub", sub).param("conversations", conversationIds.toArray(UUID[]::new))
                .query((rs, n) -> new CitationRow(rs.getObject("turn_id", UUID.class),
                        new SubjectCitation(rs.getInt("citation_number"), rs.getObject("document_id", UUID.class),
                                rs.getInt("page"), CitationUnit.valueOf(rs.getString("unit")))))
                .list();
    }

    /**
     * Serialises the erasures of one subject in the qa schema until the transaction ends, so the next batch is chosen
     * after a concurrent one committed and the counts are not reported twice.
     */
    public void lockSubject(String sub) {
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "qa.subject:" + sub)
                .query((rs, n) -> 1).single();
    }

    /**
     * Deletes up to {@code limit} conversations of the subject; turns, citations and feedback go by ON DELETE CASCADE
     * (as the table owner, ADR-0023). Returns what went, counted in the statement's own snapshot.
     */
    public SubjectHistoryCounts deleteConversations(String sub, int limit) {
        return db.jdbc().sql("""
                        WITH picked AS (SELECT id FROM qa.conversation WHERE account_id = :sub ORDER BY id LIMIT :limit),
                             gone AS (DELETE FROM qa.conversation WHERE id IN (SELECT id FROM picked) RETURNING id)
                        SELECT (SELECT count(*) FROM gone) AS conversations,
                               (SELECT count(*) FROM qa.turn WHERE conversation_id IN (SELECT id FROM gone)) AS turns,
                               (SELECT count(*) FROM qa.turn_feedback f JOIN qa.turn t ON t.id = f.turn_id
                                 WHERE t.conversation_id IN (SELECT id FROM gone)) AS feedback""")
                .param("sub", sub).param("limit", limit)
                .query((rs, n) -> new SubjectHistoryCounts(rs.getLong("conversations"), rs.getLong("turns"),
                        rs.getLong("feedback"), 0))
                .single();
    }

    /** The subject's activity days (qa.active_account_day), the only per-account counter rows. */
    public int deleteActiveDays(String sub) {
        return db.jdbc().sql("DELETE FROM qa.active_account_day WHERE account_id = :sub").param("sub", sub).update();
    }

    /** One batch through qa.redact_answers_citing (V2), the only way the application can change an answer. */
    public int redactAnswersCiting(Collection<UUID> documentIds, int batch) {
        return db.jdbc().sql("SELECT qa.redact_answers_citing(CAST(:ids AS uuid[]), :batch)")
                .param("ids", documentIds.toArray(UUID[]::new)).param("batch", batch)
                .query(Integer.class).single();
    }
}
