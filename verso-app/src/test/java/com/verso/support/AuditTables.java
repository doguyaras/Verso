package com.verso.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Test access to the "audit" schema as the container's superuser (ADR-0026). The application role cannot truncate or
 * delete (by design), and tests that count must start from an empty table; every test class shares one database.
 */
public final class AuditTables {

    private AuditTables() {}

    public static void reset() {
        try (Connection admin = admin(); Statement st = admin.createStatement()) {
            st.execute("TRUNCATE audit.event");
            // Records still waiting for delivery (ADR-0039) belong to the log being reset.
            st.execute("TRUNCATE document.outbox_event, qa.outbox_event, connector.outbox_event, work.outbox_event");
        } catch (SQLException e) {
            throw new IllegalStateException("cannot reset the audit schema", e);
        }
    }

    /**
     * The count once it reaches {@code expected}, or the last count after 10 s: outbox records (ADR-0039) reach the
     * audit log right after the commit, but on another thread.
     */
    public static long awaitCount(long expected, String sql, Object... params) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        long value = count(sql, params);
        while (value != expected && System.nanoTime() - deadline < 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100));
            value = count(sql, params);
        }
        return value;
    }

    /** A single long value of a query run as the superuser. */
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

    /** The first column of every row as text, in query order. */
    public static List<String> strings(String sql, Object... params) {
        try (Connection admin = admin(); PreparedStatement st = admin.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) st.setObject(i + 1, params[i]);
            try (ResultSet rs = st.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    /** Every row as column name to value, in query order. */
    public static List<java.util.Map<String, Object>> rowsOf(String sql, Object... params) {
        try (Connection admin = admin(); PreparedStatement st = admin.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) st.setObject(i + 1, params[i]);
            try (ResultSet rs = st.executeQuery()) {
                List<java.util.Map<String, Object>> out = new ArrayList<>();
                int columns = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int c = 1; c <= columns; c++) row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("query failed: " + sql, e);
        }
    }

    public static int update(String sql, Object... params) {
        try (Connection admin = admin(); PreparedStatement st = admin.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) st.setObject(i + 1, params[i]);
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("update failed: " + sql, e);
        }
    }

    /** Rows of one actor, as written by the application (the actor is unique per test). */
    public static long rows(String actor, String action) {
        return count("select count(*) from audit.event where actor_sub = ? and action = ?", actor, action);
    }

    /** Inserts a row with a chosen time, as the superuser (the application cannot choose it). */
    public static void insertAt(String when, String actor, String action) {
        update("insert into audit.event (occurred_at, actor_sub, action, outcome, target_type, trace_id, mode) "
                + "values (cast(? as timestamptz), ?, ?, 'SUCCESS', 'DOCUMENT', repeat('a', 32), 'local')", when, actor, action);
    }

    public static Connection admin() throws SQLException {
        return DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(), VersoPostgres.POSTGRES.getUsername(),
                VersoPostgres.POSTGRES.getPassword());
    }
}
