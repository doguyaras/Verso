package com.verso.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Test access to the "work" schema as the container's superuser (ADR-0057): every test class shares one database, and
 * tests that count runs must start from empty tables or move a lease into the past.
 */
public final class WorkTables {

    private WorkTables() {}

    /** Empties every work table; the switches go back to "every skill enabled". */
    public static void reset() {
        try (Connection admin = QaTables.admin(); Statement st = admin.createStatement()) {
            st.execute("TRUNCATE work.skill_run, work.skill_run_source, work.custom_skill, work.custom_skill_version, "
                    + "work.skill_setting");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot reset the work schema", e);
        }
    }

    /** A single long value of a query run as the superuser. */
    public static long count(String sql, Object... params) {
        try (Connection admin = QaTables.admin(); PreparedStatement st = admin.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) st.setObject(i + 1, params[i]);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("no row: " + sql);
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    /** One statement as the superuser; the number of rows it changed. */
    public static int update(String sql, Object... params) {
        try (Connection admin = QaTables.admin(); PreparedStatement st = admin.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) st.setObject(i + 1, params[i]);
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("update failed: " + sql, e);
        }
    }
}
