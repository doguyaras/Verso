package com.verso.connector.config;

import com.verso.connector.repository.ConnectorDatabase;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The connector module's own pool on the schema "connector" (ADR-0038), connected as svc_connector. Web application
 * only: the one-shot migrate run has no application connections (and no svc_connector password). Same decision as the
 * qa and audit modules (ADR-0023 option A, ADR-0026): one pool and one role per module schema, so no other module's
 * role gets a right on "connector" and svc_connector none on theirs.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({ConnectorDatabaseProperties.class, ConnectorProperties.class})
@EnableScheduling
public class ConnectorPersistenceConfiguration {

    /**
     * Lazy like Boot's pool (no connection before the first statement), with Hikari's pool metrics under its own pool
     * name. Waits for the connector migration when this context runs one (IDE, tests); in compose the migrate container
     * ran it.
     */
    @Bean(destroyMethod = "close")
    ConnectorDatabase connectorDatabase(ConnectorDatabaseProperties properties,
                                        ObjectProvider<ConnectorSchemaMigration> migration,
                                        ObjectProvider<MeterRegistry> registry) {
        migration.ifAvailable(m -> {
            if (!m.migrated()) throw new IllegalStateException("connector schema migration did not run");
        });
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("verso-connector-db");
        pool.setJdbcUrl(properties.url());
        pool.setUsername(properties.username());
        pool.setPassword(properties.password());
        pool.setMaximumPoolSize(properties.maximumPoolSize());
        pool.setMinimumIdle(properties.minimumIdle());
        pool.setConnectionTimeout(properties.connectionTimeout().toMillis());
        pool.setKeepaliveTime(properties.keepaliveTime().toMillis());
        // Start without a connection: a database that is down at start-up fails the first statement (503 for an admin
        // request, a failed run for the worker), not the application start with Hikari's PoolInitializationException.
        pool.setInitializationFailTimeout(-1);
        registry.ifAvailable(r -> pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(r)));
        return new ConnectorDatabase(pool);
    }
}
