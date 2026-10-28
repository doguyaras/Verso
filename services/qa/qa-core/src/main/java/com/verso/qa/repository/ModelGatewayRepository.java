package com.verso.qa.repository;

import com.verso.qa.api.dto.ModelBudget;
import com.verso.qa.api.dto.ModelPrice;
import com.verso.qa.api.dto.ModelRouteEntry;
import com.verso.qa.api.dto.ModelTask;
import com.verso.qa.api.dto.ModelUsageResponse;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * Routes, settings, prices, budgets and usage of the model gateway (V8, ADR-0056). Statements only; callers own the
 * transactions. Usage rows never name an account.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ModelGatewayRepository {

    /** One call to record (the egress log columns included). */
    public record Usage(String month, UUID connectionId, String model, String task, String team, boolean external,
                        String outcome, int promptTokens, int completionTokens, BigDecimal cost,
                        List<String> sentCategories, String filterPolicy) {
    }

    /** A budget row with the months its limits were already reported. */
    public record BudgetRow(String scope, String scopeKey, BigDecimal hardLimit, BigDecimal softLimit,
                            String softNotifiedMonth, String hardNotifiedMonth) {
        public ModelBudget toDto() {
            return new ModelBudget(scope, scopeKey, hardLimit, softLimit);
        }
    }

    private final QaDatabase db;

    public ModelGatewayRepository(QaDatabase db) {
        this.db = db;
    }

    // ------------------------------------------------------------------------------------------------ routes

    public Map<ModelTask, List<ModelRouteEntry>> routes() {
        Map<ModelTask, List<ModelRouteEntry>> routes = new EnumMap<>(ModelTask.class);
        for (ModelTask task : ModelTask.values()) routes.put(task, new java.util.ArrayList<>());
        db.jdbc().sql("SELECT task, connection_id, model FROM qa.model_route ORDER BY task, position")
                .query((rs, n) -> {
                    routes.get(ModelTask.valueOf(rs.getString("task")))
                            .add(new ModelRouteEntry(rs.getObject("connection_id", UUID.class), rs.getString("model")));
                    return null;
                }).list();
        routes.replaceAll((task, entries) -> List.copyOf(entries));
        return routes;
    }

    public void replaceRoute(ModelTask task, List<ModelRouteEntry> entries) {
        db.jdbc().sql("DELETE FROM qa.model_route WHERE task = :task").param("task", task.name()).update();
        for (int i = 0; i < entries.size(); i++) {
            db.jdbc().sql("INSERT INTO qa.model_route (task, position, connection_id, model) "
                            + "VALUES (:task, :position, :connection, :model)")
                    .param("task", task.name()).param("position", i).param("connection", entries.get(i).connectionId())
                    .param("model", entries.get(i).model()).update();
        }
    }

    public boolean connectionRouted(UUID connectionId) {
        return db.jdbc().sql("SELECT count(*) FROM qa.model_route WHERE connection_id = :id").param("id", connectionId)
                .query(Long.class).single() > 0;
    }

    // ------------------------------------------------------------------------------------------------ settings

    public boolean userModelChoice() {
        return db.jdbc().sql("SELECT user_model_choice FROM qa.model_gateway_setting WHERE id = 1")
                .query(Boolean.class).optional().orElse(false);
    }

    public void setUserModelChoice(boolean on, Instant now) {
        db.jdbc().sql("UPDATE qa.model_gateway_setting SET user_model_choice = :on, updated_at = :now WHERE id = 1")
                .param("on", on).param("now", Timestamp.from(now)).update();
    }

    // ------------------------------------------------------------------------------------------------ prices

    public List<ModelPrice> prices() {
        return db.jdbc().sql("SELECT * FROM qa.model_price ORDER BY connection_id, model")
                .query((rs, n) -> new ModelPrice(rs.getObject("connection_id", UUID.class), rs.getString("model"),
                        rs.getBigDecimal("input_per_million"), rs.getBigDecimal("output_per_million"),
                        rs.getString("currency"))).list();
    }

    public Optional<ModelPrice> price(UUID connectionId, String model) {
        return db.jdbc().sql("SELECT * FROM qa.model_price WHERE connection_id = :id AND model = :model")
                .param("id", connectionId).param("model", model)
                .query((rs, n) -> new ModelPrice(rs.getObject("connection_id", UUID.class), rs.getString("model"),
                        rs.getBigDecimal("input_per_million"), rs.getBigDecimal("output_per_million"),
                        rs.getString("currency"))).optional();
    }

    public void upsertPrice(ModelPrice p) {
        db.jdbc().sql("""
                        INSERT INTO qa.model_price (connection_id, model, input_per_million, output_per_million, currency)
                        VALUES (:id, :model, :in, :out, :currency)
                        ON CONFLICT (connection_id, model) DO UPDATE SET input_per_million = EXCLUDED.input_per_million,
                            output_per_million = EXCLUDED.output_per_million, currency = EXCLUDED.currency""")
                .param("id", p.connectionId()).param("model", p.model()).param("in", p.inputPerMillion())
                .param("out", p.outputPerMillion()).param("currency", p.currency()).update();
    }

    public boolean deletePrice(UUID connectionId, String model) {
        return db.jdbc().sql("DELETE FROM qa.model_price WHERE connection_id = :id AND model = :model")
                .param("id", connectionId).param("model", model).update() == 1;
    }

    // ------------------------------------------------------------------------------------------------ budgets

    public List<BudgetRow> budgets() {
        return db.jdbc().sql("SELECT * FROM qa.model_budget ORDER BY scope, scope_key")
                .query((rs, n) -> new BudgetRow(rs.getString("scope"), rs.getString("scope_key"),
                        rs.getBigDecimal("hard_limit"), rs.getBigDecimal("soft_limit"),
                        rs.getString("soft_notified_month"), rs.getString("hard_notified_month"))).list();
    }

    public void upsertBudget(ModelBudget b) {
        db.jdbc().sql("""
                        INSERT INTO qa.model_budget (scope, scope_key, hard_limit, soft_limit)
                        VALUES (:scope, :key, :hard, :soft)
                        ON CONFLICT (scope, scope_key) DO UPDATE SET hard_limit = EXCLUDED.hard_limit,
                            soft_limit = EXCLUDED.soft_limit, soft_notified_month = NULL, hard_notified_month = NULL""")
                .param("scope", b.scope()).param("key", b.scopeKey()).param("hard", b.hardLimit())
                .param("soft", b.softLimit()).update();
    }

    public boolean deleteBudget(String scope, String key) {
        return db.jdbc().sql("DELETE FROM qa.model_budget WHERE scope = :scope AND scope_key = :key")
                .param("scope", scope).param("key", key).update() == 1;
    }

    /**
     * Marks the month's soft or hard crossing as reported; true for the one caller that changed it (a conditional
     * update: several instances crossing at once report it once).
     */
    public boolean markNotified(String scope, String key, String level, String month) {
        String column = "HARD".equals(level) ? "hard_notified_month" : "soft_notified_month";
        return db.jdbc().sql("UPDATE qa.model_budget SET " + column + " = :month WHERE scope = :scope "
                        + "AND scope_key = :key AND " + column + " IS DISTINCT FROM :month")
                .param("month", month).param("scope", scope).param("key", key).update() == 1;
    }

    // ------------------------------------------------------------------------------------------------ usage

    public void insertUsage(Usage u) {
        db.jdbc().sql("""
                        INSERT INTO qa.model_usage (month, connection_id, model, task, team, external, outcome,
                            prompt_tokens, completion_tokens, cost, sent_categories, filter_policy)
                        VALUES (:month, :connection, :model, :task, :team, :external, :outcome, :prompt, :completion,
                                :cost, :categories, :policy)""")
                .param("month", u.month()).param("connection", u.connectionId()).param("model", u.model())
                .param("task", u.task()).param("team", u.team()).param("external", u.external())
                .param("outcome", u.outcome()).param("prompt", u.promptTokens()).param("completion", u.completionTokens())
                .param("cost", u.cost()).param("categories", u.sentCategories().toArray(String[]::new))
                .param("policy", u.filterPolicy()).update();
    }

    public BigDecimal spentByConnection(String month, UUID connectionId) {
        return db.jdbc().sql("SELECT coalesce(sum(cost), 0) FROM qa.model_usage WHERE month = :month "
                        + "AND connection_id = :id")
                .param("month", month).param("id", connectionId).query(BigDecimal.class).single();
    }

    public BigDecimal spentByTeam(String month, String team) {
        return db.jdbc().sql("SELECT coalesce(sum(cost), 0) FROM qa.model_usage WHERE month = :month AND team = :team")
                .param("month", month).param("team", team).query(BigDecimal.class).single();
    }

    /** Per connection, model and task; never per account. */
    public List<ModelUsageResponse.Row> usageRows(String month) {
        return db.jdbc().sql("""
                        SELECT u.connection_id, c.name, u.model, u.task, count(*) AS requests,
                               sum(u.prompt_tokens) AS prompt, sum(u.completion_tokens) AS completion,
                               sum(u.cost) AS cost, count(*) FILTER (WHERE u.outcome <> 'OK') AS failures
                        FROM qa.model_usage u LEFT JOIN qa.model_connection c ON c.id = u.connection_id
                        WHERE u.month = :month AND u.task <> 'TEST'
                        GROUP BY u.connection_id, c.name, u.model, u.task
                        ORDER BY cost DESC, requests DESC""")
                .param("month", month)
                .query((rs, n) -> new ModelUsageResponse.Row(rs.getObject("connection_id", UUID.class),
                        rs.getString("name"), rs.getString("model"), ModelTask.valueOf(rs.getString("task")),
                        rs.getLong("requests"), rs.getLong("prompt"), rs.getLong("completion"),
                        rs.getBigDecimal("cost"), rs.getLong("failures"))).list();
    }

    /** Team rows with their distinct-user count (from the digests); the caller applies the k rule. */
    public List<TeamUsage> teamUsage(String month) {
        return db.jdbc().sql("""
                        SELECT u.team, count(*) AS requests, sum(u.prompt_tokens) AS prompt,
                               sum(u.completion_tokens) AS completion, sum(u.cost) AS cost,
                               (SELECT count(*) FROM qa.model_team_reach r WHERE r.month = :month AND r.team = u.team)
                                   AS users
                        FROM qa.model_usage u
                        WHERE u.month = :month AND u.team IS NOT NULL AND u.task <> 'TEST'
                        GROUP BY u.team ORDER BY cost DESC, requests DESC""")
                .param("month", month)
                .query((rs, n) -> new TeamUsage(new ModelUsageResponse.Team(rs.getString("team"),
                        rs.getLong("requests"), rs.getLong("prompt"), rs.getLong("completion"),
                        rs.getBigDecimal("cost")), rs.getLong("users"))).list();
    }

    public record TeamUsage(ModelUsageResponse.Team team, long users) {
    }

    public void reach(String month, String team, byte[] digest) {
        db.jdbc().sql("INSERT INTO qa.model_team_reach (month, team, digest) VALUES (:month, :team, :digest) "
                        + "ON CONFLICT DO NOTHING")
                .param("month", month).param("team", team).param("digest", digest).update();
    }

    /** Retention: usage and reach rows older than the cutoff. Idempotent, so several instances may run it. */
    public int purgeBefore(Instant cutoff, String cutoffMonth) {
        int usage = db.jdbc().sql("DELETE FROM qa.model_usage WHERE occurred_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff)).update();
        int reach = db.jdbc().sql("DELETE FROM qa.model_team_reach WHERE month < :month")
                .param("month", cutoffMonth).update();
        return usage + reach;
    }
}
