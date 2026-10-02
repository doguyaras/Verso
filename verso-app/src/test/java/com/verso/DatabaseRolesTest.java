package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.support.VersoPostgres;
import com.verso.support.WithVersoPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Schema ownership is enforced by GRANT, not by convention (reference 10.1, ADR-0009). Runs against the real image
 * with the real deploy/postgres/initdb scripts and the real Flyway configuration; the application connects exactly
 * as in production: svc_document for the application, svc_document_migrate for Flyway.
 *
 * <p>"No privilege" is asserted on information_schema where a GRANT/REVOKE is concerned: PostgreSQL turns a
 * non-owner's GRANT into a silent WARNING, so an SQLState alone could prove the wrong thing (reference 10.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "management.server.port=0")
@WithVersoPostgres
class DatabaseRolesTest {

    private static final String PERMISSION_DENIED = "42501";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void application_whenConnected_usesTheDmlRoleWithItsTimeoutsAndSearchPath() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("svc_document");
        assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("10s");
        assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("3s");
        assertThat(jdbc.queryForObject("show idle_in_transaction_session_timeout", String.class)).isEqualTo("1min");
        assertThat(jdbc.queryForObject("show search_path", String.class)).isEqualTo("document, extensions");
    }

    /** 10-roles.sh: only the named roles may connect; PUBLIC has no database privilege at all. */
    @Test
    void database_whenInitialized_isClosedToPublicAndOpenToTheNamedRoles() {
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_database d, aclexplode(d.datacl) a
                where d.datname = current_database() and a.grantee = 0
                """, Integer.class)).as("privileges granted to PUBLIC").isZero();
        for (String role : new String[]{"svc_document", "svc_document_migrate", "verso_backup"}) {
            assertThat(jdbc.queryForObject("select has_database_privilege(?, current_database(), 'CONNECT')",
                    Boolean.class, role)).as(role).isTrue();
        }
    }

    @Test
    void flyway_whenApplicationStarted_ranAsTheMigrationRoleAndOwnsTheHistory() {
        assertThat(jdbc.queryForObject(
                "select tableowner from pg_tables where schemaname = 'document' and tablename = 'flyway_schema_history'",
                String.class)).isEqualTo("svc_document_migrate");
        assertThat(jdbc.queryForObject(
                "select nspowner::regrole::text from pg_namespace where nspname = 'document'", String.class))
                .isEqualTo("svc_document_migrate");
    }

    /** afterMigrate.sql: the default privileges gave the application role DML on the history; it is taken back. */
    @Test
    void applicationRole_whenMigrationsRan_hasNoPrivilegeOnTheHistoryTable() {
        assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.role_table_grants
                where grantee = 'svc_document' and table_schema = 'document' and table_name = 'flyway_schema_history'
                """, Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("delete from document.flyway_schema_history"))
                .hasRootCauseInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
    }

    @Test
    void applicationRole_whenAttemptingDdlOrOtherSchemas_isDenied() {
        for (String ddl : new String[]{
                "create table document.app_made (id int)",
                "create table public.app_made (id int)",
                "create schema app_made",
                "create extension hstore",
                "drop schema document cascade",
                "truncate document.flyway_schema_history"}) {
            assertThatThrownBy(() -> jdbc.execute(ddl)).as(ddl)
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
        }
    }

    /** Default privileges: whatever the migration role creates is usable for DML (and vectors) by the application. */
    @Test
    void migrationRoleTable_whenCreated_isWritableByTheApplicationRoleButNotDroppable() throws SQLException {
        String table = "document.probe_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection migrate = connect("svc_document_migrate", "SECRET_DB_DOCUMENT_MIGRATE_PASSWORD");
             Statement ddl = migrate.createStatement()) {
            ddl.execute("create table " + table + " (id int primary key, embedding extensions.vector(3))");
            try {
                jdbc.update("insert into " + table + " values (1, '[1,0,0]'), (2, '[0,1,0]')");
                assertThat(jdbc.queryForObject("select id from " + table + " order by embedding <=> '[0.9,0.1,0]' limit 1",
                        Integer.class)).isEqualTo(1);
                assertThat(jdbc.update("delete from " + table + " where id = 2")).isEqualTo(1);
                assertThatThrownBy(() -> jdbc.execute("drop table " + table))
                        .satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
            } finally {
                ddl.execute("drop table " + table);
            }
        }
    }

    @Test
    void backupRole_whenConnected_readsEverythingAndWritesNothing() throws SQLException {
        try (Connection backup = connect("verso_backup", "SECRET_DB_BACKUP_PASSWORD");
             Statement st = backup.createStatement()) {
            try (ResultSet rs = st.executeQuery("select count(*) from document.flyway_schema_history")) {
                assertThat(rs.next()).isTrue();
            }
            assertThatThrownBy(() -> st.executeUpdate("delete from document.flyway_schema_history"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(PERMISSION_DENIED));
        }
    }

    /**
     * deploy/postgres/initdb/05-settings.sh: statistics on, bind parameters never logged (llm-rules 2.1). Read as the
     * superuser: the application role cannot read server settings such as shared_preload_libraries, as it should.
     */
    @Test
    void server_whenStarted_hasStatisticsAndNoParameterLogging() throws SQLException {
        assertThatThrownBy(() -> jdbc.queryForObject("show shared_preload_libraries", String.class))
                .satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
        try (Connection admin = DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(),
                VersoPostgres.POSTGRES.getUsername(), VersoPostgres.POSTGRES.getPassword());
             Statement st = admin.createStatement()) {
            assertThat(single(st, "show shared_preload_libraries")).contains("pg_stat_statements");
            assertThat(single(st, "show log_parameter_max_length")).isEqualTo("0");
            assertThat(single(st, "show log_parameter_max_length_on_error")).isEqualTo("0");
            assertThat(single(st, "show log_statement")).isEqualTo("none");
            assertThat(single(st, "select extversion from pg_extension where extname = 'vector'")).startsWith("0.8.");
            assertThat(single(st, "select count(*) from extensions.pg_stat_statements")).isNotEqualTo("0");
        }
    }

    private static String single(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }

    /**
     * Phase 2 security review S1: hiding bind parameters is not enough. A failing constraint writes a DETAIL line with
     * the whole row ("Failing row contains (...)", "Key (title)=(...) already exists"), i.e. document text or file
     * names, into the PostgreSQL log. log_error_verbosity = terse drops DETAIL.
     */
    @Test
    void constraintViolation_whenRowCarriesDocumentText_neverReachesTheServerLog() throws SQLException {
        String marker = "SYNTH_DOC_TEXT_" + UUID.randomUUID().toString().replace("-", "");
        String table = "document.log_probe_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection migrate = connect("svc_document_migrate", "SECRET_DB_DOCUMENT_MIGRATE_PASSWORD");
             Statement ddl = migrate.createStatement()) {
            ddl.execute("create table " + table + " (id int primary key, title text unique, body text not null"
                    + " check (length(body) < 10))");
            try {
                jdbc.update("insert into " + table + " values (1, ?, 'short')", marker + "_TITLE");
                assertThatThrownBy(() -> jdbc.update("insert into " + table + " values (2, ?, ?)",
                        marker + "_TITLE", "ok")).as("unique violation");
                assertThatThrownBy(() -> jdbc.update("insert into " + table + " values (3, 'x', ?)",
                        marker + "_BODY")).as("check violation");
            } finally {
                ddl.execute("drop table " + table);
            }
        }
        String logs = VersoPostgres.POSTGRES.getLogs();
        assertThat(logs).as("server log after constraint violations").contains("violates");
        assertThat(logs).doesNotContain(marker);
    }

    @Test
    void server_whenStarted_logsErrorsTersely() throws SQLException {
        try (Connection admin = DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(),
                VersoPostgres.POSTGRES.getUsername(), VersoPostgres.POSTGRES.getPassword());
             Statement st = admin.createStatement()) {
            assertThat(single(st, "show log_error_verbosity")).isEqualTo("terse");
        }
    }

    private static Connection connect(String user, String secret) throws SQLException {
        return DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(), user, VersoPostgres.secret(secret));
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }
}
