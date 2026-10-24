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
                    + "qa.usage_daily_latency, qa.usage_daily_document, qa.usage_daily_feedback, qa.active_account_day");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot reset the qa schema", e);
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
