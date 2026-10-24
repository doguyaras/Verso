package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.VersoPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;

/**
 * config/verso.yml's own datasource and Flyway lines, fed the way compose feeds them: DB_HOST, DB_PORT and DB_NAME as
 * environment values (OIDC_* included), SECRET_* as config-tree properties. @VersoTestEnvironment overrides verso.document.datasource.* and
 * verso.document.flyway.*, so before this test a wrong URL or a swapped password in verso.yml passed every test (phase 2 test
 * review T6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "management.server.port=0")
@ContextConfiguration(initializers = DeployConfigTest.ComposeLikeInputs.class)
class DeployConfigTest {

    static final class ComposeLikeInputs implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            VersoPostgres.POSTGRES.start();
            TestPropertyValues.of(
                    "DB_HOST=" + VersoPostgres.POSTGRES.getHost(),
                    "DB_PORT=" + VersoPostgres.POSTGRES.getFirstMappedPort(),
                    "DB_NAME=" + VersoPostgres.DATABASE,
                    "OIDC_ISSUER=" + com.verso.platform.security.testing.TestIdentityProvider.ISSUER,
                    "OIDC_JWK_SET_URI=" + com.verso.support.TestIdp.IDP.jwkSetUri(),
                    "SECRET_DB_DOCUMENT_PASSWORD=" + VersoPostgres.secret("SECRET_DB_DOCUMENT_PASSWORD"),
                    "SECRET_DB_DOCUMENT_MIGRATE_PASSWORD=" + VersoPostgres.secret("SECRET_DB_DOCUMENT_MIGRATE_PASSWORD"),
                    "SECRET_DB_QA_PASSWORD=" + VersoPostgres.secret("SECRET_DB_QA_PASSWORD"),
                    "SECRET_DB_QA_MIGRATE_PASSWORD=" + VersoPostgres.secret("SECRET_DB_QA_MIGRATE_PASSWORD"),
                    "SECRET_DB_AUDIT_PASSWORD=" + VersoPostgres.secret("SECRET_DB_AUDIT_PASSWORD"),
                    "SECRET_DB_AUDIT_MIGRATE_PASSWORD=" + VersoPostgres.secret("SECRET_DB_AUDIT_MIGRATE_PASSWORD"),
                    "SECRET_DB_CONNECTOR_PASSWORD=" + VersoPostgres.secret("SECRET_DB_CONNECTOR_PASSWORD"),
                    "SECRET_DB_CONNECTOR_MIGRATE_PASSWORD=" + VersoPostgres.secret("SECRET_DB_CONNECTOR_MIGRATE_PASSWORD"),
                    "verso.qa.datasource.maximum-pool-size=2",
                    "verso.audit.datasource.maximum-pool-size=2",
                    "verso.connector.datasource.maximum-pool-size=2",
                    // Compose gives every model setting too (x-db-env); the provider is read by auto-configuration conditions.
                    "VERSO_AI_MODE=local", "VERSO_CHAT_PROVIDER=ollama",
                    "verso.document.datasource.maximum-pool-size=2")
                    .applyTo(context);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    org.springframework.core.env.Environment environment;

    /**
     * ADR-0023: verso.yml's qa lines, resolved like compose resolves them, reach the database as svc_qa (the qa pool
     * itself exists only in the web application), and the qa Flyway (any application type) ran as svc_qa_migrate.
     */
    @Test
    void deployConfig_whenFedLikeCompose_connectsTheQaModuleAsItsOwnRole() throws java.sql.SQLException {
        try (java.sql.Connection qa = java.sql.DriverManager.getConnection(
                environment.getRequiredProperty("verso.qa.datasource.url"),
                environment.getRequiredProperty("verso.qa.datasource.username"),
                environment.getRequiredProperty("verso.qa.datasource.password"));
             java.sql.Statement st = qa.createStatement();
             java.sql.ResultSet rs = st.executeQuery("select current_user, current_setting('search_path')")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("svc_qa");
            assertThat(rs.getString(2)).isEqualTo("qa, extensions");
        }
        assertThat(jdbc.queryForObject(
                "select tableowner from pg_tables where schemaname = 'qa' and tablename = 'flyway_schema_history'",
                String.class)).isEqualTo("svc_qa_migrate");
    }

    /** ADR-0026: the same for the audit module, resolved from verso.yml exactly like compose resolves it. */
    @Test
    void deployConfig_whenFedLikeCompose_connectsTheAuditModuleAsItsOwnRole() throws java.sql.SQLException {
        try (java.sql.Connection audit = java.sql.DriverManager.getConnection(
                environment.getRequiredProperty("verso.audit.datasource.url"),
                environment.getRequiredProperty("verso.audit.datasource.username"),
                environment.getRequiredProperty("verso.audit.datasource.password"));
             java.sql.Statement st = audit.createStatement();
             java.sql.ResultSet rs = st.executeQuery("select current_user, current_setting('search_path')")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("svc_audit");
            assertThat(rs.getString(2)).isEqualTo("audit, extensions");
        }
        assertThat(jdbc.queryForObject(
                "select tableowner from pg_tables where schemaname = 'audit' and tablename = 'flyway_schema_history'",
                String.class)).isEqualTo("svc_audit_migrate");
    }

    /** ADR-0038: the same for the connector module, resolved from verso.yml exactly like compose resolves it. */
    @Test
    void deployConfig_whenFedLikeCompose_connectsTheConnectorModuleAsItsOwnRole() throws java.sql.SQLException {
        try (java.sql.Connection connector = java.sql.DriverManager.getConnection(
                environment.getRequiredProperty("verso.connector.datasource.url"),
                environment.getRequiredProperty("verso.connector.datasource.username"),
                environment.getRequiredProperty("verso.connector.datasource.password"));
             java.sql.Statement st = connector.createStatement();
             java.sql.ResultSet rs = st.executeQuery("select current_user, current_setting('search_path')")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("svc_connector");
            assertThat(rs.getString(2)).isEqualTo("connector, extensions");
        }
        assertThat(jdbc.queryForObject(
                "select tableowner from pg_tables where schemaname = 'connector' and tablename = 'flyway_schema_history'",
                String.class)).isEqualTo("svc_connector_migrate");
    }

    @Test
    void deployConfig_whenFedLikeCompose_connectsAsTheApplicationRoleToTheVersoDatabase() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("svc_document");
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("verso");
        assertThat(jdbc.queryForObject(
                "select tableowner from pg_tables where schemaname = 'document' and tablename = 'flyway_schema_history'",
                String.class)).isEqualTo("svc_document_migrate");
    }
}
