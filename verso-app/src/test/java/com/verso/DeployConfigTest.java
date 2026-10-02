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
 * environment values (OIDC_* included), SECRET_* as config-tree properties. @VersoTestEnvironment overrides spring.datasource.* and
 * spring.flyway.*, so before this test a wrong URL or a swapped password in verso.yml passed every test (phase 2 test
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
                    "spring.datasource.hikari.maximum-pool-size=2")
                    .applyTo(context);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deployConfig_whenFedLikeCompose_connectsAsTheApplicationRoleToTheVersoDatabase() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("svc_document");
        assertThat(jdbc.queryForObject("select current_database()", String.class)).isEqualTo("verso");
        assertThat(jdbc.queryForObject(
                "select tableowner from pg_tables where schemaname = 'document' and tablename = 'flyway_schema_history'",
                String.class)).isEqualTo("svc_document_migrate");
    }
}
