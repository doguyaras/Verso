package com.verso.audit.config;

import com.verso.audit.repository.AuditDatabase;
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
 * The audit module's own pool on the schema "audit" (ADR-0026), connected as svc_audit. Web application only: the
 * one-shot migrate run has no application connections (and no svc_audit password). Same decision as the qa module
 * (ADR-0023, option A): one pool and one role per module schema, so svc_document and svc_qa get no right on "audit"
 * and svc_audit none on their schemas.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({AuditDatabaseProperties.class, AuditProperties.class})
@EnableScheduling
public class AuditPersistenceConfiguration {

    /**
     * Lazy like Boot's pool (no connection before the first statement), with Hikari's pool metrics under its own pool
     * name. Waits for the audit migration when this context runs one (IDE, tests); in compose the migrate container
     * ran it.
     */
    @Bean(destroyMethod = "close")
    AuditDatabase auditDatabase(AuditDatabaseProperties properties, ObjectProvider<AuditSchemaMigration> migration,
                                ObjectProvider<MeterRegistry> registry) {
        migration.ifAvailable(m -> {
            if (!m.migrated()) throw new IllegalStateException("audit schema migration did not run");
        });
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("verso-audit-db");
        pool.setJdbcUrl(properties.url());
        pool.setUsername(properties.username());
        pool.setPassword(properties.password());
        pool.setMaximumPoolSize(properties.maximumPoolSize());
        pool.setMinimumIdle(properties.minimumIdle());
        pool.setConnectionTimeout(properties.connectionTimeout().toMillis());
        pool.setKeepaliveTime(properties.keepaliveTime().toMillis());
        // Start without a connection: an audit database that is down at the first use then fails with an SQLException
        // that the recorder counts, not with Hikari's PoolInitializationException.
        pool.setInitializationFailTimeout(-1);
        registry.ifAvailable(r -> pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(r)));
        return new AuditDatabase(pool);
    }
}
