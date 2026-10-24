package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.support.VersoPostgres;
import com.verso.support.VersoTestEnvironment;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The second module schema (ADR-0023): "qa" has its own two roles and the GRANT boundary of reference 10.1 holds in
 * both directions. Real image, real init scripts, real Flyway; checked with real logins (a catalog query passes without
 * any privilege, review D1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "management.server.port=0")
@VersoTestEnvironment
class QaSchemaRolesTest {

    private static final String PERMISSION_DENIED = "42501";

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void qaRoles_whenConnected_haveTheirTimeoutsAndOwnSearchPath() throws SQLException {
        try (Connection app = connect("svc_qa", "SECRET_DB_QA_PASSWORD"); Statement st = app.createStatement()) {
            assertThat(single(st, "show statement_timeout")).isEqualTo("10s");
            assertThat(single(st, "show lock_timeout")).isEqualTo("3s");
            assertThat(single(st, "show idle_in_transaction_session_timeout")).isEqualTo("1min");
            assertThat(single(st, "show search_path")).isEqualTo("qa, extensions");
        }
        try (Connection migrate = connect("svc_qa_migrate", "SECRET_DB_QA_MIGRATE_PASSWORD");
             Statement st = migrate.createStatement()) {
            assertThat(single(st, "show lock_timeout")).isEqualTo("10s");
            assertThat(single(st, "show statement_timeout")).isEqualTo("0");
        }
        assertThat(jdbc.queryForObject("select nspowner::regrole::text from pg_namespace where nspname = 'qa'",
                String.class)).isEqualTo("svc_qa_migrate");
        assertThat(jdbc.queryForObject("select tableowner from pg_tables where schemaname = 'qa' "
                + "and tablename = 'flyway_schema_history'", String.class)).isEqualTo("svc_qa_migrate");
    }

    /** AGENTS.md section 4: no module reaches another module's schema, not even by accident in native SQL. */
    @Test
    void schemas_whenCrossed_areDeniedInBothDirections() throws SQLException {
        assertThatThrownBy(() -> jdbc.queryForObject("select count(*) from qa.conversation", Long.class))
                .as("svc_document into qa").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
        try (Connection app = connect("svc_qa", "SECRET_DB_QA_PASSWORD"); Statement st = app.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("select count(*) from document.document"))
                    .as("svc_qa into document").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
            assertThatThrownBy(() -> st.execute("create table qa.app_made (id int)"))
                    .as("svc_qa DDL").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
        }
        assertThat(jdbc.queryForObject("select has_schema_privilege('svc_document', 'qa', 'USAGE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select has_schema_privilege('svc_qa', 'document', 'USAGE')", Boolean.class)).isFalse();
    }

    /** V1__qa_history: turns are append-only, rollups cannot be deleted, the history table is the owner's. */
    @Test
    void qaTables_whenMigrated_giveTheApplicationRoleOnlyWhatItNeeds() {
        assertThat(privilege("qa.conversation", "UPDATE")).isTrue();
        assertThat(privilege("qa.conversation", "DELETE")).isTrue();
        assertThat(privilege("qa.turn_feedback", "UPDATE")).isTrue();
        for (String table : new String[]{"qa.turn", "qa.active_account_day"}) {
            assertThat(privilege(table, "INSERT")).as(table + " INSERT").isTrue();
            assertThat(privilege(table, "DELETE")).as(table + " DELETE").isTrue();
            assertThat(privilege(table, "UPDATE")).as(table + " UPDATE").isFalse();
        }
        // Deleted only with their turn (ON DELETE CASCADE runs with the owner's rights; HistoryRetentionTest).
        assertThat(privilege("qa.turn_citation", "INSERT")).isTrue();
        assertThat(privilege("qa.turn_citation", "UPDATE")).isFalse();
        assertThat(privilege("qa.turn_citation", "DELETE")).isFalse();
        assertThat(privilege("qa.turn_feedback", "DELETE")).isFalse();
        for (String table : new String[]{"qa.usage_daily", "qa.usage_daily_latency", "qa.usage_daily_document",
                "qa.usage_daily_feedback"}) {
            assertThat(privilege(table, "UPDATE")).as(table + " UPDATE").isTrue();
            assertThat(privilege(table, "DELETE")).as(table + " DELETE").isFalse();
        }
        assertThat(com.verso.support.QaTables.count("""
                select count(*) from information_schema.role_table_grants
                where grantee = 'svc_qa' and table_schema = 'qa' and table_name = 'flyway_schema_history'
                """)).as("afterMigrate.sql took the history back").isZero();
    }

    /**
     * The upgrade path (README "Yükseltme"): 40-modules.sh adds a missing module's roles and schema to a live server,
     * changes nothing that exists, never logs a password and stops when a secret is missing.
     */
    @Test
    void modulesScript_whenRunOnALiveServer_addsWhatIsMissingAndNothingElse() throws Exception {
        var rerun = VersoPostgres.POSTGRES.execInContainer("bash", "-c",
                "POSTGRES_USER=postgres POSTGRES_DB=verso bash /docker-entrypoint-initdb.d/40-modules.sh");
        assertThat(rerun.getExitCode()).as(rerun.getStderr()).isZero();
        try (Connection app = connect("svc_qa", "SECRET_DB_QA_PASSWORD")) {
            assertThat(app.isValid(2)).as("an existing role keeps its password").isTrue();
        }

        String probe = "probeupgrade";
        var added = VersoPostgres.POSTGRES.execInContainer("bash", "-c", "mkdir -p /tmp/probe-secrets && "
                + "printf 'probe-migrate-pw-Q7' > /tmp/probe-secrets/SECRET_DB_PROBEUPGRADE_MIGRATE_PASSWORD && "
                + "printf 'probe-app-pw-Q7' > /tmp/probe-secrets/SECRET_DB_PROBEUPGRADE_PASSWORD && "
                + "VERSO_SECRETS_DIR=/tmp/probe-secrets VERSO_INIT_MODULES=" + probe
                + " POSTGRES_USER=postgres POSTGRES_DB=verso bash /docker-entrypoint-initdb.d/40-modules.sh");
        try {
            assertThat(added.getExitCode()).as(added.getStderr()).isZero();
            assertThat(jdbc.queryForObject("select nspowner::regrole::text from pg_namespace where nspname = ?",
                    String.class, probe)).isEqualTo("svc_" + probe + "_migrate");
            try (Connection migrate = DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(),
                    "svc_" + probe + "_migrate", "probe-migrate-pw-Q7");
                 Statement ddl = migrate.createStatement()) {
                ddl.execute("create table " + probe + ".t (id int primary key)");
            }
            try (Connection app = DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(), "svc_" + probe,
                    "probe-app-pw-Q7");
                 Statement st = app.createStatement()) {
                assertThat(st.executeUpdate("insert into " + probe + ".t values (1)")).as("default privileges").isOne();
                assertThat(single(st, "show statement_timeout")).isEqualTo("10s");
                assertThatThrownBy(() -> st.execute("drop table " + probe + ".t"))
                        .satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
            }
            assertThat(VersoPostgres.POSTGRES.getLogs()).doesNotContain("probe-migrate-pw-Q7", "probe-app-pw-Q7");
        } finally {
            try (Connection admin = DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(),
                    VersoPostgres.POSTGRES.getUsername(), VersoPostgres.POSTGRES.getPassword());
                 Statement st = admin.createStatement()) {
                st.execute("drop schema if exists " + probe + " cascade");
                // The migration role first: its default privileges name the application role.
                for (String role : new String[]{"svc_" + probe + "_migrate", "svc_" + probe}) {
                    st.execute("do $$ begin if exists (select 1 from pg_roles where rolname = '" + role + "') then "
                            + "execute 'drop owned by " + role + "'; end if; end $$");
                }
                st.execute("drop role if exists svc_" + probe + "_migrate");
                st.execute("drop role if exists svc_" + probe);
            }
        }

        var noSecret = VersoPostgres.POSTGRES.execInContainer("bash", "-c", "mkdir -p /tmp/no-secrets && "
                + "VERSO_SECRETS_DIR=/tmp/no-secrets POSTGRES_USER=postgres POSTGRES_DB=verso "
                + "bash /docker-entrypoint-initdb.d/40-modules.sh");
        assertThat(noSecret.getExitCode()).isEqualTo(1);
        assertThat(noSecret.getStderr()).contains("missing, empty or unreadable");
    }

    /** Asked as the superuser: svc_document cannot even resolve a name in "qa", which is the point. */
    private static boolean privilege(String table, String privilege) {
        return com.verso.support.QaTables.count("select has_table_privilege('svc_qa', ?, ?)::int", table, privilege) == 1;
    }

    private static Connection connect(String user, String secret) throws SQLException {
        return DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(), user, VersoPostgres.secret(secret));
    }

    private static String single(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) return sql.getSQLState();
        }
        return null;
    }
}
