package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.VersoPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.yaml.snakeyaml.Yaml;

/**
 * The compose "migrate" service (ADR-0007 #48) runs the application image with the arguments in compose.yaml: no web
 * server, no application DataSource, Flyway only. This test starts VersoApp with exactly those arguments, read from
 * compose.yaml, against the real database. Phase 2: a readiness group including "db" made this mode fail to start on
 * a fresh stack (no DataSource, no db health contributor), and no test started the application this way.
 */
class MigrateModeTest {

    @Test
    void migrateMode_whenStartedWithTheComposeArguments_runsFlywayWithoutApplicationDataSource() throws Exception {
        VersoPostgres.POSTGRES.start();
        List<String> args = new ArrayList<>(composeArguments("migrate"));
        args.add("--spring.flyway.url=" + VersoPostgres.POSTGRES.getJdbcUrl());
        args.add("--spring.flyway.user=svc_document_migrate");
        args.add("--spring.flyway.password=" + VersoPostgres.secret("SECRET_DB_DOCUMENT_MIGRATE_PASSWORD"));
        // The qa schema's Flyway (ADR-0023) runs in the same one-shot container, as its own migration role.
        args.add("--verso.qa.flyway.url=" + VersoPostgres.POSTGRES.getJdbcUrl());
        args.add("--verso.qa.flyway.user=svc_qa_migrate");
        args.add("--verso.qa.flyway.password=" + VersoPostgres.secret("SECRET_DB_QA_MIGRATE_PASSWORD"));
        args.add("--verso.audit.flyway.url=" + VersoPostgres.POSTGRES.getJdbcUrl());
        args.add("--verso.audit.flyway.user=svc_audit_migrate");
        args.add("--verso.audit.flyway.password=" + VersoPostgres.secret("SECRET_DB_AUDIT_MIGRATE_PASSWORD"));
        // Deploy profile values compose would give; the application password is deliberately absent.
        args.add("--DB_HOST=unused");
        args.add("--DB_PORT=1");
        args.add("--DB_NAME=unused");

        try (ConfigurableApplicationContext context = SpringApplication.run(VersoApp.class, args.toArray(String[]::new))) {
            assertThat(context.getBeanNamesForType(DataSource.class)).as("application DataSource").isEmpty();
            assertThat(context.getBeanNamesForType(org.flywaydb.core.Flyway.class)).hasSize(1);
            // Phase 4: no ingestion worker may run in the one-shot migrate container, and no model client is built.
            assertThat(context.getBeanNamesForType(com.verso.document.worker.IngestionWorker.class)).isEmpty();
            assertThat(context.getBeanNamesForType(org.springframework.ai.embedding.EmbeddingModel.class)).isEmpty();
            // ADR-0023: the qa schema is migrated here, but no qa pool is opened (no svc_qa password in this container).
            assertThat(context.getBeanNamesForType(com.verso.qa.config.QaSchemaMigration.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(com.verso.qa.repository.QaDatabase.class)).isEmpty();
            // ADR-0026: the same for the audit schema (no svc_audit password in this container either).
            assertThat(context.getBeanNamesForType(com.verso.audit.config.AuditSchemaMigration.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(com.verso.audit.repository.AuditDatabase.class)).isEmpty();
        }
        try (Connection admin = DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(),
                VersoPostgres.POSTGRES.getUsername(), VersoPostgres.POSTGRES.getPassword());
             Statement st = admin.createStatement();
             ResultSet rs = st.executeQuery("select schemaname, tableowner from pg_tables where schemaname in "
                     + "('document', 'qa', 'audit') and tablename = 'flyway_schema_history' order by schemaname")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("audit");
            assertThat(rs.getString(2)).isEqualTo("svc_audit_migrate");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(2)).isEqualTo("svc_document_migrate");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("qa");
            assertThat(rs.getString(2)).isEqualTo("svc_qa_migrate");
        }
    }

    @SuppressWarnings("unchecked")
    static List<String> composeArguments(String service) throws IOException {
        String yaml = Files.readString(VersoPostgres.repoRoot().resolve("compose.yaml"), StandardCharsets.UTF_8);
        Map<String, Object> services = (Map<String, Object>) ((Map<String, Object>) new Yaml().load(yaml)).get("services");
        Object command = ((Map<String, Object>) services.get(service)).get("command");
        assertThat(command).as(service + " command").isInstanceOf(List.class);
        return ((List<Object>) command).stream().map(String::valueOf).toList();
    }
}
