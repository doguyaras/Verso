package com.verso.qa.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * Turns of a conversation, their citation references and ratings (ADR-0023). Turns are written once and deleted,
 * never updated (the application role has no UPDATE on them). Reads by turn id always carry the account and the
 * conversation, so a turn id alone opens nothing (IDOR, reference 6.5).
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TurnRepository {

    /** What is stored for one answer. Question and answer are personal data: never logged, never in toString. */
    public record NewTurn(UUID conversationId, String accountId, String question, byte[] questionHash, String answer,
                          String outcome, String mode, String chatModel, int retrievalMs, int chatMs, int totalMs,
                          LocalDate day, Instant createdAt) {

        @Override
        public String toString() {
            return "NewTurn[conversationId=" + conversationId + ", outcome=" + outcome + "]";
        }
    }

    public record CitationRow(UUID turnId, int number, UUID documentId, int page, String unit) {
    }

    /** A stored turn with the owner's rating (rating columns are null without one). */
    public record TurnRow(UUID id, String question, String answer, String outcome, String mode, String chatModel,
                          int totalMs, Instant createdAt, String rating, String reason, Integer citationNumber,
                          Instant ratedAt) {

        @Override
        public String toString() {
            return "TurnRow[id=" + id + ", outcome=" + outcome + "]";
        }
    }

    /** An owned turn: what a rating needs (its day for the rollup). */
    public record OwnedTurn(UUID id, LocalDate day) {
    }

    /** "Not found" questions of a day range asked by at least k accounts; {@code sample} is one of the equal texts. */
    public record UnansweredGroup(String sample, long distinctUsers, long asked) {

        @Override
        public String toString() {
            return "UnansweredGroup[distinctUsers=" + distinctUsers + ", asked=" + asked + "]";
        }
    }

    private final QaDatabase db;

    public TurnRepository(QaDatabase db) {
        this.db = db;
    }

    /** Stores the turn; the id comes from the database (uuidv7). */
    public UUID insert(NewTurn turn) {
        return db.jdbc().sql("INSERT INTO qa.turn (conversation_id, account_id, question, question_hash, answer, outcome, "
                        + "mode, chat_model, retrieval_ms, chat_ms, total_ms, day, created_at) VALUES (:conversation, "
                        + ":account, :question, :hash, :answer, :outcome, :mode, :model, :retrievalMs, :chatMs, :totalMs, "
                        + ":day, :createdAt) RETURNING id")
                .param("conversation", turn.conversationId()).param("account", turn.accountId())
                .param("question", turn.question()).param("hash", turn.questionHash()).param("answer", turn.answer())
                .param("outcome", turn.outcome()).param("mode", turn.mode()).param("model", turn.chatModel())
                .param("retrievalMs", turn.retrievalMs()).param("chatMs", turn.chatMs()).param("totalMs", turn.totalMs())
                .param("day", turn.day()).param("createdAt", Timestamp.from(turn.createdAt()))
                .query(UUID.class).single();
    }

    public void insertCitation(UUID turnId, int number, UUID documentId, int page, String unit) {
        db.jdbc().sql("INSERT INTO qa.turn_citation (turn_id, citation_number, document_id, page, unit) "
                        + "VALUES (:turn, :number, :document, :page, :unit)")
                .param("turn", turnId).param("number", number).param("document", documentId).param("page", page)
                .param("unit", unit).update();
    }

    public int countInConversation(UUID conversationId) {
        return db.jdbc().sql("SELECT count(*) FROM qa.turn WHERE conversation_id = :conversation")
                .param("conversation", conversationId).query(Integer.class).single();
    }

    /** The turns of one of the account's conversations, oldest first, with the owner's rating. */
    public List<TurnRow> list(String accountId, UUID conversationId) {
        return db.jdbc().sql("SELECT t.id, t.question, t.answer, t.outcome, t.mode, t.chat_model, t.total_ms, t.created_at, "
                        + "f.rating, f.reason, f.citation_number, f.updated_at AS rated_at FROM qa.turn t "
                        + "LEFT JOIN qa.turn_feedback f ON f.turn_id = t.id "
                        + "WHERE t.account_id = :account AND t.conversation_id = :conversation ORDER BY t.created_at, t.id")
                .param("account", accountId).param("conversation", conversationId)
                .query(TurnRepository::mapTurn).list();
    }

    public List<CitationRow> citations(String accountId, UUID conversationId) {
        return db.jdbc().sql("SELECT c.turn_id, c.citation_number, c.document_id, c.page, c.unit FROM qa.turn_citation c "
                        + "JOIN qa.turn t ON t.id = c.turn_id "
                        + "WHERE t.account_id = :account AND t.conversation_id = :conversation "
                        + "ORDER BY c.turn_id, c.citation_number")
                .param("account", accountId).param("conversation", conversationId)
                .query((rs, n) -> new CitationRow(rs.getObject("turn_id", UUID.class), rs.getInt("citation_number"),
                        rs.getObject("document_id", UUID.class), rs.getInt("page"), rs.getString("unit")))
                .list();
    }

    /** The turn when it belongs to this conversation of this account; otherwise nothing (no existence oracle). */
    public Optional<OwnedTurn> findOwned(String accountId, UUID conversationId, UUID turnId) {
        return db.jdbc().sql("SELECT id, day FROM qa.turn WHERE id = :turn AND conversation_id = :conversation "
                        + "AND account_id = :account")
                .param("turn", turnId).param("conversation", conversationId).param("account", accountId)
                .query((rs, n) -> new OwnedTurn(rs.getObject("id", UUID.class), rs.getObject("day", LocalDate.class)))
                .optional();
    }

    public boolean hasCitation(UUID turnId, int number) {
        return db.jdbc().sql("SELECT count(*) FROM qa.turn_citation WHERE turn_id = :turn AND citation_number = :number")
                .param("turn", turnId).param("number", number).query(Integer.class).single() > 0;
    }

    /**
     * Retention, first step: up to {@code limit} turns (with their citations and ratings) of conversations started
     * before the cutoff. A conversation lives retention-days from its start, all of its turns with it.
     */
    public int purgeOfExpiredConversations(Instant cutoff, int limit) {
        return db.jdbc().sql("DELETE FROM qa.turn WHERE id IN (SELECT t.id FROM qa.conversation c "
                        + "JOIN qa.turn t ON t.conversation_id = c.id WHERE c.created_at < :cutoff LIMIT :limit)")
                .param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
    }

    /**
     * k-anonymity (ADR-0023): groups of equal normalised "not found" questions of the day range that at least
     * {@code minUsers} distinct accounts asked; smaller groups never leave the database.
     */
    public List<UnansweredGroup> unanswered(LocalDate from, LocalDate to, int minUsers, int limit) {
        return db.jdbc().sql("SELECT min(question) AS sample, count(DISTINCT account_id) AS users, count(*) AS asked "
                        + "FROM qa.turn WHERE outcome = 'NOT_FOUND' AND day BETWEEN :from AND :to "
                        + "GROUP BY question_hash HAVING count(DISTINCT account_id) >= :minUsers "
                        + "ORDER BY users DESC, asked DESC, question_hash LIMIT :limit")
                .param("from", from).param("to", to).param("minUsers", minUsers).param("limit", limit)
                .query((rs, n) -> new UnansweredGroup(rs.getString("sample"), rs.getLong("users"), rs.getLong("asked")))
                .list();
    }

    private static TurnRow mapTurn(ResultSet rs, int rowNum) throws SQLException {
        Timestamp rated = rs.getTimestamp("rated_at");
        return new TurnRow(
                rs.getObject("id", UUID.class),
                rs.getString("question"),
                rs.getString("answer"),
                rs.getString("outcome"),
                rs.getString("mode"),
                rs.getString("chat_model"),
                rs.getInt("total_ms"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("rating"),
                rs.getString("reason"),
                (Integer) rs.getObject("citation_number"),
                rated == null ? null : rated.toInstant());
    }
}
