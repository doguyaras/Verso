package com.verso.qa.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Conversations of one account (ADR-0023). Every statement of the API carries the account: another account's
 * conversation is never read, renamed or deleted, it simply does not exist for the caller (IDOR, reference 6.5). The
 * purge statement is the retention job's and has no account on purpose.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConversationRepository {

    /** A conversation and how many turns it still holds. */
    public record ConversationRow(UUID id, String accountId, String title, Instant createdAt, Instant updatedAt,
                                  int turnCount) {
    }

    private static final String COLUMNS = "c.id, c.account_id, c.title, c.created_at, c.updated_at, "
            + "(SELECT count(*) FROM qa.turn t WHERE t.conversation_id = c.id) AS turn_count";
    private static final RowMapper<ConversationRow> ROW = ConversationRepository::map;

    private final QaDatabase db;

    public ConversationRepository(QaDatabase db) {
        this.db = db;
    }

    /** A new conversation; the id comes from the database (uuidv7, reference 10.3). */
    public ConversationRow insert(String accountId, String title, Instant now) {
        return db.jdbc().sql("INSERT INTO qa.conversation (account_id, title, created_at, updated_at) "
                        + "VALUES (:account, :title, :now, :now) RETURNING id, account_id, title, created_at, updated_at, "
                        + "0 AS turn_count")
                .param("account", accountId).param("title", title).param("now", Timestamp.from(now))
                .query(ROW).single();
    }

    public Optional<ConversationRow> find(String accountId, UUID id) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM qa.conversation c WHERE c.account_id = :account AND c.id = :id")
                .param("account", accountId).param("id", id).query(ROW).optional();
    }

    /**
     * The conversation, locked until the transaction ends: turns of one conversation are added one at a time, so its
     * turn limit holds exactly (reference 4.3, no check-then-act).
     */
    public Optional<ConversationRow> lock(String accountId, UUID id) {
        return db.jdbc().sql("SELECT c.id, c.account_id, c.title, c.created_at, c.updated_at, 0 AS turn_count "
                        + "FROM qa.conversation c WHERE c.account_id = :account AND c.id = :id FOR UPDATE")
                .param("account", accountId).param("id", id).query(ROW).optional();
    }

    public void touch(UUID id, Instant now) {
        db.jdbc().sql("UPDATE qa.conversation SET updated_at = :now WHERE id = :id")
                .param("id", id).param("now", Timestamp.from(now)).update();
    }

    public List<ConversationRow> list(String accountId, int page, int size) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM qa.conversation c WHERE c.account_id = :account "
                        + "ORDER BY c.updated_at DESC, c.id DESC LIMIT :size OFFSET :offset")
                .param("account", accountId).param("size", size).param("offset", (long) page * size)
                .query(ROW).list();
    }

    public long count(String accountId) {
        return db.jdbc().sql("SELECT count(*) FROM qa.conversation WHERE account_id = :account")
                .param("account", accountId).query(Long.class).single();
    }

    /** Only the title: updated_at stays the last turn's time, so a rename neither reorders the list nor delays retention. */
    public Optional<ConversationRow> rename(String accountId, UUID id, String title) {
        return db.jdbc().sql("UPDATE qa.conversation c SET title = :title "
                        + "WHERE c.account_id = :account AND c.id = :id RETURNING " + COLUMNS)
                .param("account", accountId).param("id", id).param("title", title)
                .query(ROW).optional();
    }

    /** Removes the conversation; its turns, citations and ratings go with it (ON DELETE CASCADE). */
    public boolean delete(String accountId, UUID id) {
        return db.jdbc().sql("DELETE FROM qa.conversation WHERE account_id = :account AND id = :id")
                .param("account", accountId).param("id", id).update() == 1;
    }

    /** Up to {@code limit} conversations of the account; the caller repeats until none is left (short transactions). */
    public int deleteSomeOf(String accountId, int limit) {
        return db.jdbc().sql("DELETE FROM qa.conversation WHERE id IN (SELECT id FROM qa.conversation "
                        + "WHERE account_id = :account LIMIT :limit)")
                .param("account", accountId).param("limit", limit).update();
    }

    /**
     * The retention job's lock for the current transaction (reference 10.4: pg_try_advisory_xact_lock, "someone else
     * is on it, skip"); released at commit, so it also works behind a transaction-mode pooler.
     */
    public boolean tryLockRetention() {
        return Boolean.TRUE.equals(db.jdbc().sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "qa.history-retention").query(Boolean.class).single());
    }

    /**
     * Retention, second step: up to {@code limit} conversations started before the cutoff whose turns are gone
     * (TurnRepository.purgeOfExpiredConversations ran first, in bounded batches, so no single statement cascades
     * through thousands of turns). A conversation that a request holds locked (a turn being added) is skipped
     * (SKIP LOCKED) and left to the next run; the request itself starts a new conversation for an expired one
     * (ConversationServiceImpl), so the race only concerns rows that are past the retention anyway.
     */
    public int purgeEmpty(Instant cutoff, int limit) {
        return db.jdbc().sql("DELETE FROM qa.conversation WHERE id IN (SELECT c.id FROM qa.conversation c "
                        + "WHERE c.created_at < :cutoff AND NOT EXISTS (SELECT 1 FROM qa.turn t WHERE t.conversation_id = c.id) "
                        + "ORDER BY c.created_at LIMIT :limit FOR UPDATE SKIP LOCKED)")
                .param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
    }

    private static ConversationRow map(ResultSet rs, int rowNum) throws SQLException {
        return new ConversationRow(
                rs.getObject("id", UUID.class),
                rs.getString("account_id"),
                rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getInt("turn_count"));
    }
}
