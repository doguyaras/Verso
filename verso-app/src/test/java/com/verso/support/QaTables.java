package com.verso.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Test access to the "qa" schema as the container's superuser (ADR-0023). The application role cannot delete the usage
 * rollups (by design), and tests that count must start from empty tables; every test class shares one database.
 */
public final class QaTables {

    private QaTables() {}

    /** Empties every qa table: history, ratings, rollups and activity rows. */
    public static void reset() {
        try (Connection admin = admin(); Statement st = admin.createStatement()) {
            st.execute("TRUNCATE qa.conversation, qa.turn, qa.turn_citation, qa.turn_feedback, qa.usage_daily, "
                    + "qa.usage_daily_latency, qa.usage_daily_document, qa.usage_daily_feedback, qa.active_account_day, "
                    // ADR-0049: the answer memory and its golden set.
                    + "qa.knowledge_card, qa.knowledge_card_citation, qa.knowledge_card_source, "
                    + "qa.knowledge_card_verification, qa.knowledge_card_dispute, qa.knowledge_card_event, "
                    + "qa.turn_knowledge_card, qa.golden_case, qa.eval_run, qa.eval_case_result, "
                    // ADR-0052: diagnostics and hooks (the rule set stays; its switches go back to the defaults).
                    + "qa.turn_question_vector, qa.diagnostic_run, qa.question_cluster, qa.contradiction_candidate, "
                    + "qa.hook_notification, qa.hook_eval_request, "
                    // ADR-0053: skills (procedures) and their golden-set results.
                    + "qa.skill, qa.skill_version, qa.skill_step, qa.skill_step_source, qa.skill_trigger, "
                    + "qa.skill_golden_case, qa.skill_approval, qa.skill_event, qa.skill_eval_result, "
                    // ADR-0054: suggested relations of the system model.
                    + "qa.system_model_suggestion, "
                    // ADR-0056: the model gateway (connections, routes, prices, budgets, usage).
                    + "qa.model_route, qa.model_price, qa.model_budget, qa.model_usage, qa.model_team_reach, "
                    + "qa.model_connection");
            st.execute("UPDATE qa.hook_rule SET enabled = default_enabled");
            st.execute("UPDATE qa.model_gateway_setting SET user_model_choice = false");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot reset the qa schema", e);
        }
    }

    /**
     * Empties the model gateway's tables only (ADR-0056). Gateway tests call it after each test: a cloud connection
     * left enabled would stop the next local-mode context from starting (the startup check, llm-rules 1.1).
     */
    public static void resetGateway() {
        try (Connection admin = admin(); Statement st = admin.createStatement()) {
            st.execute("TRUNCATE qa.model_route, qa.model_price, qa.model_budget, qa.model_usage, qa.model_team_reach, "
                    + "qa.model_connection");
            st.execute("UPDATE qa.model_gateway_setting SET user_model_choice = false");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot reset the model gateway tables", e);
        }
    }

    /** A single long value of a query run as the superuser (one optional string parameter). */
    public static long count(String sql, Object... params) {
        try (Connection admin = admin(); PreparedStatement st = admin.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) st.setObject(i + 1, params[i]);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("no row: " + sql);
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    /** Runs a statement as the superuser, e.g. to backdate rows for the retention tests. */
    public static int update(String sql, Object... params) {
        try (Connection admin = admin(); PreparedStatement st = admin.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) st.setObject(i + 1, params[i]);
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("update failed: " + sql, e);
        }
    }

    public static Connection admin() throws SQLException {
        return DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(), VersoPostgres.POSTGRES.getUsername(),
                VersoPostgres.POSTGRES.getPassword());
    }
}
