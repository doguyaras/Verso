package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.support.AuditTables;
import com.verso.support.VersoPostgres;
import com.verso.support.VersoTestEnvironment;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The third module schema (ADR-0026): "audit" has its own two roles and the audit table is append-only for the
 * application role. Real image, real init scripts, real Flyway; checked with real logins, because a catalog query
 * passes without any privilege (phase 2 review D1) and the point is the error PostgreSQL gives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "management.server.port=0")
@VersoTestEnvironment
class AuditSchemaRolesTest {

    private static final String PERMISSION_DENIED = "42501";
    private static final String CHECK_VIOLATION = "23514";
    private static final String INVALID_PARAMETER = "22023";
    private static final String INSERT = "insert into audit.event (actor_sub, actor_roles, action, outcome, target_type, "
            + "detail_code, trace_id, mode) values ('sub-1', '{verso-user}', 'QUESTION_ASKED', 'SUCCESS', 'TURN', ?, "
            + "repeat('b', 32), 'local')";

    @BeforeEach
    void setUp() {
        AuditTables.reset();
    }

    @Test
    void auditRoles_whenConnected_haveTheirTimeoutsAndOwnSearchPath() throws SQLException {
        try (Connection app = connect("svc_audit", "SECRET_DB_AUDIT_PASSWORD"); Statement st = app.createStatement()) {
            assertThat(single(st, "show statement_timeout")).isEqualTo("10s");
            assertThat(single(st, "show lock_timeout")).isEqualTo("3s");
            assertThat(single(st, "show idle_in_transaction_session_timeout")).isEqualTo("1min");
            assertThat(single(st, "show search_path")).isEqualTo("audit, extensions");
        }
        try (Connection migrate = connect("svc_audit_migrate", "SECRET_DB_AUDIT_MIGRATE_PASSWORD");
             Statement st = migrate.createStatement()) {
            assertThat(single(st, "show lock_timeout")).isEqualTo("10s");
            assertThat(single(st, "show statement_timeout")).isEqualTo("0");
        }
        assertThat(AuditTables.strings("select nspowner::regrole::text from pg_namespace where nspname = 'audit'"))
                .containsExactly("svc_audit_migrate");
    }

    /** The core guarantee (security-rules 7, reference 10.1): INSERT and SELECT, nothing else. */
    @Test
    void appRole_whenItTriesToChangeOrRemoveARow_isDeniedByTheDatabase() throws SQLException {
        try (Connection app = connect("svc_audit", "SECRET_DB_AUDIT_PASSWORD"); Statement st = app.createStatement()) {
            assertThat(st.executeUpdate(INSERT.replace("?", "'ANSWERED'"))).as("INSERT").isOne();
            assertThat(single(st, "select count(*) from audit.event")).as("SELECT").isEqualTo("1");

            assertThatThrownBy(() -> st.executeUpdate("update audit.event set actor_sub = 'someone-else'"))
                    .as("UPDATE").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
            assertThatThrownBy(() -> st.executeUpdate("delete from audit.event"))
                    .as("DELETE").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
            assertThatThrownBy(() -> st.execute("truncate audit.event"))
                    .as("TRUNCATE").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
            assertThatThrownBy(() -> st.execute("drop table audit.event"))
                    .as("DROP").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
            assertThatThrownBy(() -> st.execute("create table audit.app_made (id int)"))
                    .as("DDL").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
            assertThatThrownBy(() -> st.execute("alter table audit.event add column free_text text"))
                    .as("ALTER").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
        }
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = 'sub-1'")).isOne();
        for (String privilege : new String[]{"UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER"}) {
            assertThat(AuditTables.count("select has_table_privilege('svc_audit', 'audit.event', ?)::int", privilege))
                    .as(privilege).isZero();
        }
        for (String privilege : new String[]{"INSERT", "SELECT"}) {
            assertThat(AuditTables.count("select has_table_privilege('svc_audit', 'audit.event', ?)::int", privilege))
                    .as(privilege).isOne();
        }
        assertThat(AuditTables.count("""
                select count(*) from information_schema.role_table_grants
                where grantee = 'svc_audit' and table_schema = 'audit' and table_name = 'flyway_schema_history'
                """)).as("afterMigrate.sql took the history back").isZero();
    }

    /** AGENTS.md section 4: no module reaches another module's schema, not even by accident in native SQL. */
    @Test
    void schemas_whenCrossed_areDeniedInEveryDirection() throws SQLException {
        try (Connection app = connect("svc_audit", "SECRET_DB_AUDIT_PASSWORD"); Statement st = app.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("select count(*) from document.document"))
                    .as("svc_audit into document").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
            assertThatThrownBy(() -> st.executeQuery("select count(*) from qa.conversation"))
                    .as("svc_audit into qa").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
        }
        for (String[] other : new String[][]{{"svc_document", "SECRET_DB_DOCUMENT_PASSWORD"}, {"svc_qa", "SECRET_DB_QA_PASSWORD"}}) {
            try (Connection app = connect(other[0], other[1]); Statement st = app.createStatement()) {
                assertThatThrownBy(() -> st.executeQuery("select count(*) from audit.event"))
                        .as(other[0] + " into audit").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
                assertThatThrownBy(() -> st.executeQuery("select audit.purge_expired(now() - interval '400 days', 10)"))
                        .as(other[0] + " calling the retention function")
                        .satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
            }
            assertThat(AuditTables.count("select has_schema_privilege(?, 'audit', 'USAGE')::int", other[0])).isZero();
        }
        assertThat(AuditTables.count("select has_schema_privilege('svc_audit', 'document', 'USAGE')::int")).isZero();
        assertThat(AuditTables.count("select has_schema_privilege('svc_audit', 'qa', 'USAGE')::int")).isZero();
    }

    /** "Ids only" is enforced by the table itself, not just by the publishers (security-rules 4/5). */
    @Test
    void table_whenGivenFreeTextOrForeignRoles_refusesTheRow() throws SQLException {
        try (Connection app = connect("svc_audit", "SECRET_DB_AUDIT_PASSWORD"); Statement st = app.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate(INSERT.replace("?", "'what is the salary of Ayse?'")))
                    .as("a sentence in detail_code").satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
            assertThatThrownBy(() -> st.executeUpdate(INSERT.replace("?", "null").replace("{verso-user}", "{group:finance}")))
                    .as("an identity provider group as role").satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
            assertThatThrownBy(() -> st.executeUpdate(INSERT.replace("?", "null").replace("repeat('b', 32)", "'203.0.113.7'")))
                    .as("an address as trace id").satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
            assertThatThrownBy(() -> st.executeUpdate(INSERT.replace("?", "null").replace("'local'", "'hybrid'")))
                    .as("unknown mode").satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
            assertThatThrownBy(() -> st.executeUpdate(INSERT.replace("?", "null").replace("'SUCCESS'", "'maybe'")))
                    .as("unknown outcome").satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
            assertThatThrownBy(() -> st.executeUpdate(INSERT.replace("?", "null").replace("'QUESTION_ASKED'", "'Delete my file.pdf'")))
                    .as("a sentence as action").satisfies(e -> assertThat(sqlState(e)).isEqualTo(CHECK_VIOLATION));
        }
        assertThat(AuditTables.count("select count(*) from audit.event")).isZero();
    }

    /**
     * The retention function is the only deletion path and it is narrow: only svc_audit, only rows older than 30 days,
     * bounded batches. Even svc_audit cannot ask for recent history to be erased.
     */
    @Test
    void purgeFunction_whenCalled_refusesTheWrongCallerAndRecentCutoffs() throws SQLException {
        AuditTables.insertAt("now", "actor-recent", "DOCUMENT_UPLOADED");
        AuditTables.insertAt("2020-01-01T00:00:00Z", "actor-old", "DOCUMENT_UPLOADED");
        try (Connection app = connect("svc_audit", "SECRET_DB_AUDIT_PASSWORD"); Statement st = app.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("select audit.purge_expired(now(), 100)"))
                    .as("cutoff now").satisfies(e -> assertThat(sqlState(e)).isEqualTo(INVALID_PARAMETER));
            assertThatThrownBy(() -> st.executeQuery("select audit.purge_expired(now() - interval '29 days', 100)"))
                    .as("cutoff 29 days").satisfies(e -> assertThat(sqlState(e)).isEqualTo(INVALID_PARAMETER));
            assertThatThrownBy(() -> st.executeQuery("select audit.purge_expired(null, 100)"))
                    .as("no cutoff").satisfies(e -> assertThat(sqlState(e)).isEqualTo(INVALID_PARAMETER));
            assertThatThrownBy(() -> st.executeQuery("select audit.purge_expired(now() - interval '400 days', 5001)"))
                    .as("batch too large").satisfies(e -> assertThat(sqlState(e)).isEqualTo(INVALID_PARAMETER));
        }
        assertThat(AuditTables.count("select count(*) from audit.event")).as("nothing was deleted").isEqualTo(2);

        // Another login that can reach the schema (verso_backup reads every schema) is refused by the function.
        try (Connection backup = connect("verso_backup", "SECRET_DB_BACKUP_PASSWORD"); Statement st = backup.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("select audit.purge_expired(now() - interval '400 days', 100)"))
                    .as("verso_backup").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
        }
        try (Connection migrate = connect("svc_audit_migrate", "SECRET_DB_AUDIT_MIGRATE_PASSWORD");
             Statement st = migrate.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("select audit.purge_expired(now() - interval '400 days', 100)"))
                    .as("the owner's login").satisfies(e -> assertThat(sqlState(e)).isEqualTo(PERMISSION_DENIED));
        }
        assertThat(AuditTables.count("select count(*) from audit.event")).isEqualTo(2);

        try (Connection app = connect("svc_audit", "SECRET_DB_AUDIT_PASSWORD"); Statement st = app.createStatement()) {
            assertThat(single(st, "select audit.purge_expired(now() - interval '31 days', 100)")).isEqualTo("1");
        }
        assertThat(AuditTables.strings("select actor_sub from audit.event")).containsExactly("actor-recent");
    }

    /** The database assigns the time and the id: the application's INSERT list never names them. */
    @Test
    void insert_whenMadeByTheApplication_getsTheDatabaseTimeAndAnId() throws SQLException {
        try (Connection app = connect("svc_audit", "SECRET_DB_AUDIT_PASSWORD"); Statement st = app.createStatement()) {
            st.executeUpdate(INSERT.replace("?", "null"));
        }
        assertThat(AuditTables.count("select count(*) from audit.event where occurred_at > now() - interval '1 minute' "
                + "and id is not null")).isOne();
        assertThat(UUID.fromString(AuditTables.strings("select id::text from audit.event").get(0)).version()).isEqualTo(7);
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
