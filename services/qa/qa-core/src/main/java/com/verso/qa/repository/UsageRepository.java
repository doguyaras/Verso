package com.verso.qa.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * Daily usage rollups (ADR-0023): raised in the transaction that stores a turn or a rating. Each statement is an atomic
 * upsert (INSERT ... ON CONFLICT DO UPDATE), safe with several instances. Counts only; the one per-account table
 * (active_account_day) is deleted with the history.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UsageRepository {

    /** Marks the rating rows without a reason (NOT NULL key column). */
    public static final String NO_REASON = "NONE";

    public record DayRow(LocalDate day, long questions, long answered, long notFound, long uncited, long localMode,
                         long cloudMode, long activeUsers) {
    }

    public record LatencyRow(LocalDate day, int bucketMs, long turns) {
    }

    public record FeedbackCountRow(LocalDate day, String rating, String reason, long feedback) {
    }

    public record DocumentCountRow(UUID documentId, long citations) {
    }

    private final QaDatabase db;

    public UsageRepository(QaDatabase db) {
        this.db = db;
    }

    /** True when this is the account's first stored question of the day (it then counts as an active user). */
    public boolean markActive(LocalDate day, String accountId) {
        return db.jdbc().sql("INSERT INTO qa.active_account_day (day, account_id) VALUES (:day, :account) "
                        + "ON CONFLICT DO NOTHING")
                .param("day", day).param("account", accountId).update() == 1;
    }

    public void addQuestion(LocalDate day, String outcome, String mode, boolean newActiveUser) {
        db.jdbc().sql("INSERT INTO qa.usage_daily AS u (day, questions, answered, not_found, uncited, local_mode, "
                        + "cloud_mode, active_users) VALUES (:day, 1, :answered, :notFound, :uncited, :local, :cloud, :active) "
                        + "ON CONFLICT (day) DO UPDATE SET questions = u.questions + 1, "
                        + "answered = u.answered + EXCLUDED.answered, not_found = u.not_found + EXCLUDED.not_found, "
                        + "uncited = u.uncited + EXCLUDED.uncited, local_mode = u.local_mode + EXCLUDED.local_mode, "
                        + "cloud_mode = u.cloud_mode + EXCLUDED.cloud_mode, active_users = u.active_users + EXCLUDED.active_users")
                .param("day", day)
                .param("answered", "ANSWERED".equals(outcome) ? 1 : 0)
                .param("notFound", "NOT_FOUND".equals(outcome) ? 1 : 0)
                .param("uncited", "UNCITED".equals(outcome) ? 1 : 0)
                .param("local", "local".equals(mode) ? 1 : 0)
                .param("cloud", "cloud".equals(mode) ? 1 : 0)
                .param("active", newActiveUser ? 1 : 0)
                .update();
    }

    public void addLatency(LocalDate day, int bucketMs) {
        db.jdbc().sql("INSERT INTO qa.usage_daily_latency AS l (day, bucket_ms, turns) VALUES (:day, :bucket, 1) "
                        + "ON CONFLICT (day, bucket_ms) DO UPDATE SET turns = l.turns + 1")
                .param("day", day).param("bucket", bucketMs).update();
    }

    public void addCitation(LocalDate day, UUID documentId, int citations) {
        db.jdbc().sql("INSERT INTO qa.usage_daily_document AS d (day, document_id, citations) VALUES (:day, :document, :n) "
                        + "ON CONFLICT (day, document_id) DO UPDATE SET citations = d.citations + EXCLUDED.citations")
                .param("day", day).param("document", documentId).param("n", citations).update();
    }

    /** Moves one rating count: from the old rating (if any) to the new one, on the day of the rated turn. */
    public void addFeedback(LocalDate day, String rating, String reason, int delta) {
        db.jdbc().sql("INSERT INTO qa.usage_daily_feedback AS f (day, rating, reason, feedback) "
                        + "VALUES (:day, :rating, :reason, GREATEST(:delta, 0)) "
                        + "ON CONFLICT (day, rating, reason) DO UPDATE SET feedback = f.feedback + :delta")
                .param("day", day).param("rating", rating).param("reason", reason == null ? NO_REASON : reason)
                .param("delta", delta).update();
    }

    public List<DayRow> days(LocalDate from, LocalDate to) {
        return db.jdbc().sql("SELECT day, questions, answered, not_found, uncited, local_mode, cloud_mode, active_users "
                        + "FROM qa.usage_daily WHERE day BETWEEN :from AND :to ORDER BY day")
                .param("from", from).param("to", to)
                .query((rs, n) -> new DayRow(rs.getObject("day", LocalDate.class), rs.getLong("questions"),
                        rs.getLong("answered"), rs.getLong("not_found"), rs.getLong("uncited"), rs.getLong("local_mode"),
                        rs.getLong("cloud_mode"), rs.getLong("active_users")))
                .list();
    }

    public List<LatencyRow> latency(LocalDate from, LocalDate to) {
        return db.jdbc().sql("SELECT day, bucket_ms, turns FROM qa.usage_daily_latency WHERE day BETWEEN :from AND :to")
                .param("from", from).param("to", to)
                .query((rs, n) -> new LatencyRow(rs.getObject("day", LocalDate.class), rs.getInt("bucket_ms"),
                        rs.getLong("turns")))
                .list();
    }

    public List<FeedbackCountRow> feedback(LocalDate from, LocalDate to) {
        return db.jdbc().sql("SELECT day, rating, reason, feedback FROM qa.usage_daily_feedback "
                        + "WHERE day BETWEEN :from AND :to")
                .param("from", from).param("to", to)
                .query((rs, n) -> new FeedbackCountRow(rs.getObject("day", LocalDate.class), rs.getString("rating"),
                        rs.getString("reason"), rs.getLong("feedback")))
                .list();
    }

    public List<DocumentCountRow> topDocuments(LocalDate from, LocalDate to, int limit) {
        return db.jdbc().sql("SELECT document_id, sum(citations) AS citations FROM qa.usage_daily_document "
                        + "WHERE day BETWEEN :from AND :to GROUP BY document_id ORDER BY citations DESC, document_id LIMIT :limit")
                .param("from", from).param("to", to).param("limit", limit)
                .query((rs, n) -> new DocumentCountRow(rs.getObject("document_id", UUID.class), rs.getLong("citations")))
                .list();
    }

    /** Every document a stored answer ever cited (rollups are kept, so this survives the history retention). */
    public List<UUID> citedEver(int limit) {
        return db.jdbc().sql("SELECT DISTINCT document_id FROM qa.usage_daily_document LIMIT :limit")
                .param("limit", limit).query(UUID.class).list();
    }

    /** Distinct accounts that asked in the range, as far as the retained active_account_day rows reach. */
    public long activeUsers(LocalDate from, LocalDate to) {
        return db.jdbc().sql("SELECT count(DISTINCT account_id) FROM qa.active_account_day WHERE day BETWEEN :from AND :to")
                .param("from", from).param("to", to).query(Long.class).single();
    }

    /** "Delete all my conversations": the account's activity rows go too (the anonymous counts stay). */
    public int forgetAccount(String accountId) {
        return db.jdbc().sql("DELETE FROM qa.active_account_day WHERE account_id = :account")
                .param("account", accountId).update();
    }

    /** Retention: up to {@code limit} activity rows of days before {@code cutoffDay}. */
    public int purgeActive(LocalDate cutoffDay, int limit) {
        return db.jdbc().sql("DELETE FROM qa.active_account_day WHERE (day, account_id) IN (SELECT day, account_id "
                        + "FROM qa.active_account_day WHERE day < :cutoff LIMIT :limit)")
                .param("cutoff", cutoffDay).param("limit", limit).update();
    }
}
