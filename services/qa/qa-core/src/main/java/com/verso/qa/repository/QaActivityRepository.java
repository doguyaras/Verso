package com.verso.qa.repository;

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
 * The qa module's reads for the activity centre (ADR-0058): windowed, bounded by {@code limit}, newest first, counts
 * and fixed words only. No query returns an account id ({@code created_by} and approvers are never selected) and no
 * card question or answer: names are added by the caller only after its readability check.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QaActivityRepository {

    /** A golden-set check run (ADR-0049). */
    public record EvalRun(UUID id, String trigger, String status, int cases, int found, int missed, int sourceLost,
                          Double recall, Double baseline, boolean alert, Instant startedAt, Instant finishedAt) {
    }

    /** A diagnostics run (ADR-0052). */
    public record HealthRun(UUID id, String trigger, String status, int questions, int clusters,
                            int contradictionsChecked, Instant startedAt, Instant finishedAt) {
    }

    /** A hook notification (ADR-0052): rule, action, trigger, subject type and a fixed detail word. */
    public record HookRow(UUID id, String ruleKey, String action, String trigger, String subjectType, UUID subjectId,
                          String detail, String severity, Instant createdAt, Instant readAt) {
    }

    /** A card or a procedure in a given state, with the time it entered it. */
    public record StateRow(UUID id, String state, String reason, int version, String name, Instant createdAt,
                           Instant updatedAt) {
    }

    /** A contradiction candidate still seen by the scan. */
    public record ContradictionRow(UUID id, UUID documentA, int pageA, UUID documentB, int pageB, int differences,
                                   Instant firstSeenAt, Instant lastSeenAt) {
    }

    private final QaDatabase db;

    public QaActivityRepository(QaDatabase db) {
        this.db = db;
    }

    public List<EvalRun> evalRuns(Instant from, Instant to, int limit) {
        return db.jdbc().sql("SELECT id, run_trigger, status, cases, found, missed, source_lost, recall_at_k, "
                        + "baseline_recall, alert, started_at, finished_at FROM qa.eval_run "
                        + "WHERE started_at >= :from AND started_at < :to ORDER BY started_at DESC, id DESC LIMIT :limit")
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("limit", limit)
                .query(QaActivityRepository::evalRun).list();
    }

    public Optional<EvalRun> evalRun(UUID id) {
        return db.jdbc().sql("SELECT id, run_trigger, status, cases, found, missed, source_lost, recall_at_k, "
                        + "baseline_recall, alert, started_at, finished_at FROM qa.eval_run WHERE id = :id")
                .param("id", id).query(QaActivityRepository::evalRun).optional();
    }

    public List<HealthRun> healthRuns(Instant from, Instant to, int limit) {
        return db.jdbc().sql("SELECT id, run_trigger, status, questions, clusters, contradictions_checked, started_at, "
                        + "finished_at FROM qa.diagnostic_run WHERE started_at >= :from AND started_at < :to "
                        + "ORDER BY started_at DESC, id DESC LIMIT :limit")
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("limit", limit)
                .query(QaActivityRepository::healthRun).list();
    }

    public Optional<HealthRun> healthRun(UUID id) {
        return db.jdbc().sql("SELECT id, run_trigger, status, questions, clusters, contradictions_checked, started_at, "
                        + "finished_at FROM qa.diagnostic_run WHERE id = :id")
                .param("id", id).query(QaActivityRepository::healthRun).optional();
    }

    public List<HookRow> hooks(Instant from, Instant to, int limit) {
        return db.jdbc().sql("SELECT id, rule_key, action, trigger_type, subject_type, subject_id, detail, severity, "
                        + "created_at, read_at FROM qa.hook_notification WHERE created_at >= :from AND created_at < :to "
                        + "ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("limit", limit)
                .query(QaActivityRepository::hook).list();
    }

    public Optional<HookRow> hook(UUID id) {
        return db.jdbc().sql("SELECT id, rule_key, action, trigger_type, subject_type, subject_id, detail, severity, "
                        + "created_at, read_at FROM qa.hook_notification WHERE id = :id")
                .param("id", id).query(QaActivityRepository::hook).optional();
    }

    /** Cards in {@code state} whose last change is before {@code to}, newest change first (state index). */
    public List<StateRow> cards(String state, Instant to, int limit) {
        return db.jdbc().sql("SELECT id, state, stale_reason, cycle, NULL AS name, created_at, updated_at "
                        + "FROM qa.knowledge_card WHERE state = :state AND updated_at < :to "
                        + "ORDER BY updated_at DESC, id DESC LIMIT :limit")
                .param("state", state).param("to", Timestamp.from(to)).param("limit", limit)
                .query(QaActivityRepository::stateRow).list();
    }

    /**
     * The canonical questions of these cards, in one statement. Card text is document content: the caller asks only
     * for cards it has checked the viewer may read (ADR-0028).
     */
    public java.util.Map<UUID, String> questions(java.util.Collection<UUID> cardIds) {
        java.util.Map<UUID, String> questions = new java.util.HashMap<>();
        if (cardIds.isEmpty()) return questions;
        db.jdbc().sql("SELECT id, question FROM qa.knowledge_card WHERE id IN (:ids)").param("ids", cardIds)
                .query((rs, n) -> java.util.Map.entry(rs.getObject("id", UUID.class), rs.getString("question"))).list()
                .forEach(e -> questions.put(e.getKey(), e.getValue()));
        return questions;
    }

    /** Procedures in {@code state}; the name is the procedure's public slug (ADR-0053), shown like the admin list does. */
    public List<StateRow> skills(String state, Instant to, int limit) {
        return db.jdbc().sql("SELECT id, state, stale_reason, version, name, created_at, updated_at FROM qa.skill "
                        + "WHERE state = :state AND updated_at < :to ORDER BY updated_at DESC, id DESC LIMIT :limit")
                .param("state", state).param("to", Timestamp.from(to)).param("limit", limit)
                .query(QaActivityRepository::stateRow).list();
    }

    /** Contradictions the scan saw since {@code seenSince}, first found before {@code to}. */
    public List<ContradictionRow> contradictions(Instant seenSince, Instant to, int limit) {
        return db.jdbc().sql("SELECT id, document_a, page_a, document_b, page_b, differences, first_seen_at, "
                        + "last_seen_at FROM qa.contradiction_candidate WHERE last_seen_at >= :since "
                        + "AND first_seen_at < :to ORDER BY first_seen_at DESC, id DESC LIMIT :limit")
                .param("since", Timestamp.from(seenSince)).param("to", Timestamp.from(to)).param("limit", limit)
                .query((rs, n) -> new ContradictionRow(rs.getObject("id", UUID.class),
                        rs.getObject("document_a", UUID.class), rs.getInt("page_a"),
                        rs.getObject("document_b", UUID.class), rs.getInt("page_b"), rs.getInt("differences"),
                        instant(rs, "first_seen_at"), instant(rs, "last_seen_at"))).list();
    }

    private static EvalRun evalRun(ResultSet rs, int n) throws SQLException {
        return new EvalRun(rs.getObject("id", UUID.class), rs.getString("run_trigger"), rs.getString("status"),
                rs.getInt("cases"), rs.getInt("found"), rs.getInt("missed"), rs.getInt("source_lost"),
                decimal(rs, "recall_at_k"), decimal(rs, "baseline_recall"), rs.getBoolean("alert"),
                instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private static HealthRun healthRun(ResultSet rs, int n) throws SQLException {
        return new HealthRun(rs.getObject("id", UUID.class), rs.getString("run_trigger"), rs.getString("status"),
                rs.getInt("questions"), rs.getInt("clusters"), rs.getInt("contradictions_checked"),
                instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private static HookRow hook(ResultSet rs, int n) throws SQLException {
        return new HookRow(rs.getObject("id", UUID.class), rs.getString("rule_key"), rs.getString("action"),
                rs.getString("trigger_type"), rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                rs.getString("detail"), rs.getString("severity"), instant(rs, "created_at"), instant(rs, "read_at"));
    }

    private static StateRow stateRow(ResultSet rs, int n) throws SQLException {
        return new StateRow(rs.getObject("id", UUID.class), rs.getString("state"), rs.getString("stale_reason"),
                rs.getInt(4), rs.getString("name"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Double decimal(ResultSet rs, String column) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
