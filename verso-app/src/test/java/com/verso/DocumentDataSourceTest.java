package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.config.DocumentPersistenceConfiguration.DocumentDataSource;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.VersoTestEnvironment;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;

/**
 * The document module's own pool (ADR-0039), like qa's and audit's: the one DataSource bean of the application is the
 * document pool ("verso-document-db", svc_document), Spring's JdbcClient and the document repositories run on it, and
 * there is no other default connection (Boot's DataSource and Flyway auto-configuration are excluded). Its Flyway ran
 * as svc_document_migrate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class DocumentDataSourceTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    DocumentRepository documents;

    @Autowired
    JdbcClient jdbc;

    @Test
    void dataSource_whenTheApplicationStarts_isOnlyTheDocumentPool() {
        assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("documentDataSource");
        DocumentDataSource dataSource = context.getBean(DocumentDataSource.class);
        assertThat(((HikariDataSource) dataSource.pool()).getPoolName()).isEqualTo("verso-document-db");
        assertThat(((HikariDataSource) dataSource.pool()).getUsername()).isEqualTo("svc_document");
        assertThat(context.getBeanNamesForType(org.flywaydb.core.Flyway.class)).as("Boot's Flyway is off").isEmpty();
        assertThat(context.getBean(com.verso.document.config.DocumentSchemaMigration.class).migrated()).isTrue();
    }

    @Test
    void documentRepositories_whenTheyQuery_runAsSvcDocumentOnTheDocumentPool() throws Exception {
        assertThat(jdbc.sql("select current_user").query(String.class).single()).isEqualTo("svc_document");
        assertThat(repositoryJdbc(documents)).as("the repository's JdbcClient is the application's").isSameAs(jdbc);

        String account = "acct-ds-" + UUID.randomUUID();
        var row = documents.insert(account, "pool.pdf", com.verso.document.api.enums.DocumentFormat.PDF, 1, null,
                Instant.now());
        assertThat(jdbc.sql("select count(*) from document.document where id = :id").param("id", row.id())
                .query(Long.class).single()).isOne();
        HikariDataSource pool = (HikariDataSource) context.getBean(DocumentDataSource.class).pool();
        assertThat(pool.getHikariPoolMXBean().getTotalConnections()).as("the statement used this pool").isPositive();
        jdbc.sql("delete from document.document where id = :id").param("id", row.id()).update();
    }

    @Test
    void ingestionWorker_whenTheSwitchIsOnByDefault_exists() {
        assertThat(context.getBeanNamesForType(IngestionWorker.class)).hasSize(1);
    }

    private static Object repositoryJdbc(DocumentRepository repository) throws ReflectiveOperationException {
        Object target = AopTestUtils.getUltimateTargetObject(repository);
        Field field = target.getClass().getDeclaredField("jdbc");
        field.setAccessible(true);
        return field.get(target);
    }
}
