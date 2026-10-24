package com.verso.document.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

/**
 * The document module's own pool on the schema "document" (ADR-0039), connected as svc_document, declared here like
 * qa's (ADR-0023) and audit's (ADR-0026) instead of Boot's auto-configured spring.datasource. In every application
 * type, as Boot's pool was (non-web contexts such as the role tests use it); the one-shot migrate run switches it off
 * with verso.document.datasource.enabled=false (compose "migrate": no application connections, no svc_document
 * password), as it used to exclude Boot's DataSourceAutoConfiguration.
 *
 * <p><b>Why this one is a bean and qa's and audit's are not.</b> Spring's JDBC support (JdbcClient, JdbcTemplate, the
 * transaction manager behind TransactionTemplate and {@code @Transactional}) and the readiness contributor "db" each
 * need exactly one DataSource bean, and the document module is the one that uses them. So the application has one
 * DataSource bean, and it is explicitly the document pool: Boot's DataSourceAutoConfiguration and
 * FlywayAutoConfiguration are excluded (application.yml), there is no other "default" connection. qa and audit keep
 * theirs out of the context so they can never be injected by type.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "verso.document.datasource.enabled", matchIfMissing = true)
@EnableConfigurationProperties(DocumentDatabaseProperties.class)
public class DocumentPersistenceConfiguration {

    /**
     * Lazy like Boot 4.1's {@code connection-fetch: lazy} (no connection before the first statement, reference 10.5),
     * with Hikari's pool metrics under its own pool name. Waits for the document migration when this context runs one
     * (IDE, tests); in compose the migrate container ran it.
     */
    @Bean(destroyMethod = "close")
    DocumentDataSource documentDataSource(DocumentDatabaseProperties properties,
                                          ObjectProvider<DocumentSchemaMigration> migration,
                                          ObjectProvider<MeterRegistry> registry) {
        migration.ifAvailable(m -> {
            if (!m.migrated()) throw new IllegalStateException("document schema migration did not run");
        });
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("verso-document-db");
        pool.setJdbcUrl(properties.url());
        pool.setUsername(properties.username());
        pool.setPassword(properties.password());
        pool.setMaximumPoolSize(properties.maximumPoolSize());
        pool.setMinimumIdle(properties.minimumIdle());
        pool.setConnectionTimeout(properties.connectionTimeout().toMillis());
        pool.setKeepaliveTime(properties.keepaliveTime().toMillis());
        // Start without a connection, as before: a database that is down at start-up fails the first statement (503),
        // and readiness ("db") reports it, instead of failing the start.
        pool.setInitializationFailTimeout(-1);
        registry.ifAvailable(r -> pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(r)));
        return new DocumentDataSource(pool);
    }

    /**
     * The document pool behind a lazy proxy, with known defaults so that opening a transaction never fetches a
     * connection just to read them (same as QaDatabase); closes the pool at shutdown.
     */
    public static final class DocumentDataSource extends LazyConnectionDataSourceProxy implements AutoCloseable {

        private final HikariDataSource pool;

        DocumentDataSource(HikariDataSource pool) {
            super(pool);
            this.pool = pool;
            setDefaultAutoCommit(true);
            setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }

        /** The pool itself (pool metrics and tests); never handed to another module. */
        public DataSource pool() {
            return pool;
        }

        @Override
        public void close() {
            pool.close();
        }
    }
}
