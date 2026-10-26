package com.verso.qa.repository;

import com.verso.qa.api.dto.KnowledgeEvalRunResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * The golden set and its runs (ADR-0049). The run row is the distributed lock of the check: at most one RUNNING row
 * exists (partial unique index), it carries a claim token and a lease, and a run whose lease ran out is marked FAILED
 * by the next claimer. A SCHEDULED run exists at most once per day unless it failed.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeEvalRepository {

    private static final String RUN_COLUMNS = "id, run_trigger, status, started_at, finished_at, cases, found, missed, "
            + "source_lost, skipped, top_k, recall_at_k, baseline_recall, source_drift, alert";

    /** One active golden case. */
    public record GoldenCase(UUID cardId, String question, String state, String embeddingModel) {

        @Override
        public String toString() {
            return "GoldenCase[cardId=" + cardId + ", state=" + state + "]";
        }
    }

    /** The counts of a finished run. */
    public record Totals(int cases, int found, int missed, int sourceLost, int skipped, Double recall, Double baseline,
                         Double drift, boolean alert) {
    }

    private final QaDatabase db;

    public KnowledgeEvalRepository(QaDatabase db) {
        this.db = db;
    }

    /** Marks a RUNNING run whose lease ran out as FAILED (its instance died); returns how many. */
    public int expireLeases(Instant now) {
        return db.jdbc().sql("UPDATE qa.eval_run SET status = 'FAILED', claim_token = NULL, locked_until = NULL, "
                        + "finished_at = :now WHERE status = 'RUNNING' AND locked_until < :now")
                .param("now", Timestamp.from(now)).update();
    }

    /** The new run, or empty when another run is RUNNING (or today's scheduled run exists). */
    public Optional<UUID> claim(String trigger, LocalDate runDay, int topK, UUID claimToken, Instant lockedUntil,
                                Instant now) {
        return db.jdbc().sql("INSERT INTO qa.eval_run (run_trigger, run_day, status, claim_token, locked_until, top_k, "
                        + "started_at) VALUES (:trigger, :day, 'RUNNING', :token, :until, :k, :now) "
                        + "ON CONFLICT DO NOTHING RETURNING id")
                .param("trigger", trigger).param("day", runDay).param("token", claimToken)
                .param("until", Timestamp.from(lockedUntil)).param("k", topK).param("now", Timestamp.from(now))
                .query(UUID.class).optional();
    }

    public boolean renew(UUID runId, UUID claimToken, Instant lockedUntil) {
        return db.jdbc().sql("UPDATE qa.eval_run SET locked_until = :until WHERE id = :run AND claim_token = :token")
                .param("run", runId).param("token", claimToken).param("until", Timestamp.from(lockedUntil)).update() == 1;
    }

    /** Active cases, oldest export first, at most {@code limit}. */
    public List<GoldenCase> activeCases(int limit) {
        return db.jdbc().sql("SELECT g.card_id, c.question, c.state, c.embedding_model FROM qa.golden_case g "
                        + "JOIN qa.knowledge_card c ON c.id = g.card_id WHERE g.active "
                        + "ORDER BY g.exported_at, g.card_id LIMIT :limit")
                .param("limit", limit)
                .query((rs, n) -> new GoldenCase(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4))).list();
    }

    public void recordCase(UUID runId, UUID cardId, String outcome, Integer rank) {
        db.jdbc().sql("INSERT INTO qa.eval_case_result (run_id, card_id, outcome, rank) VALUES (:run, :card, :outcome, "
                        + ":rank) ON CONFLICT DO NOTHING")
                .param("run", runId).param("card", cardId).param("outcome", outcome).param("rank", rank).update();
    }

    public boolean finish(UUID runId, UUID claimToken, Totals totals, Instant now) {
        return db.jdbc().sql("UPDATE qa.eval_run SET status = 'SUCCEEDED', claim_token = NULL, locked_until = NULL, "
                        + "cases = :cases, found = :found, missed = :missed, source_lost = :lost, skipped = :skipped, "
                        + "recall_at_k = :recall, baseline_recall = :baseline, source_drift = :drift, alert = :alert, "
                        + "finished_at = :now WHERE id = :run AND claim_token = :token")
                .param("run", runId).param("token", claimToken).param("cases", totals.cases())
                .param("found", totals.found()).param("missed", totals.missed()).param("lost", totals.sourceLost())
                .param("skipped", totals.skipped()).param("recall", decimal(totals.recall()))
                .param("baseline", decimal(totals.baseline())).param("drift", decimal(totals.drift()))
                .param("alert", totals.alert()).param("now", Timestamp.from(now)).update() == 1;
    }

    public void fail(UUID runId, UUID claimToken, Instant now) {
        db.jdbc().sql("UPDATE qa.eval_run SET status = 'FAILED', claim_token = NULL, locked_until = NULL, "
                        + "finished_at = :now WHERE id = :run AND claim_token = :token")
                .param("run", runId).param("token", claimToken).param("now", Timestamp.from(now)).update();
    }

    /** The recall of the latest successful run other than {@code runId} that had something to compare. */
    public Optional<Double> baseline(UUID runId) {
        return db.jdbc().sql("SELECT recall_at_k FROM qa.eval_run WHERE status = 'SUCCEEDED' AND id <> :run "
                        + "AND recall_at_k IS NOT NULL ORDER BY started_at DESC, id DESC LIMIT 1")
                .param("run", runId).query(BigDecimal.class).optional().map(BigDecimal::doubleValue);
    }

    public Optional<KnowledgeEvalRunResponse> find(UUID runId) {
        return db.jdbc().sql("SELECT " + RUN_COLUMNS + " FROM qa.eval_run WHERE id = :run")
                .param("run", runId).query(KnowledgeEvalRepository::run).optional();
    }

    /** The latest successful run (gauges). */
    public Optional<KnowledgeEvalRunResponse> latestSucceeded() {
        return db.jdbc().sql("SELECT " + RUN_COLUMNS + " FROM qa.eval_run WHERE status = 'SUCCEEDED' "
                        + "ORDER BY started_at DESC, id DESC LIMIT 1")
                .query(KnowledgeEvalRepository::run).optional();
    }

    public List<KnowledgeEvalRunResponse> list(int page, int size) {
        return db.jdbc().sql("SELECT " + RUN_COLUMNS + " FROM qa.eval_run ORDER BY started_at DESC, id DESC "
                        + "LIMIT :size OFFSET :offset")
                .param("size", size).param("offset", (long) page * size).query(KnowledgeEvalRepository::run).list();
    }

    public long count() {
        return db.jdbc().sql("SELECT count(*) FROM qa.eval_run").query(Long.class).single();
    }

    public int purge(Instant cutoff, int limit) {
        return db.jdbc().sql("DELETE FROM qa.eval_run WHERE id IN (SELECT id FROM qa.eval_run WHERE status <> 'RUNNING' "
                        + "AND started_at < :cutoff LIMIT :limit)")
                .param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static KnowledgeEvalRunResponse run(ResultSet rs, int n) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new KnowledgeEvalRunResponse(rs.getObject("id", UUID.class), rs.getString("run_trigger"),
                rs.getString("status"), rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant(), rs.getInt("cases"), rs.getInt("found"),
                rs.getInt("missed"), rs.getInt("source_lost"), rs.getInt("skipped"), rs.getInt("top_k"),
                ratio(rs, "recall_at_k"), ratio(rs, "baseline_recall"), ratio(rs, "source_drift"), rs.getBoolean("alert"));
    }

    private static Double ratio(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }
}
