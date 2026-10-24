package com.verso.qa.config;

import com.verso.qa.repository.QaDatabase;
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
 * The qa module's own pool on the schema "qa" (ADR-0023), connected as svc_qa. Web application only: the one-shot
 * migrate run has no application connections (and no svc_qa password).
 *
 * <p>Second-schema decision (ADR-0023, option A): one pool and one role per module schema, so the GRANT boundary of
 * reference 10.1 holds between document and qa as well; svc_document gets no right on "qa" and svc_qa none on
 * "document".
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({QaDatabaseProperties.class, QaHistoryProperties.class, UsageMetricsProperties.class})
@EnableScheduling
public class QaPersistenceConfiguration {

    /**
     * Lazy like Boot's pool (no connection before the first statement), with Hikari's pool metrics under its own pool
     * name. Waits for the qa migration when this context runs one (IDE, tests); in compose the migrate container ran it.
     */
    @Bean(destroyMethod = "close")
    QaDatabase qaDatabase(QaDatabaseProperties properties, ObjectProvider<QaSchemaMigration> migration,
                          ObjectProvider<MeterRegistry> registry) {
        migration.ifAvailable(m -> {
            if (!m.migrated()) throw new IllegalStateException("qa schema migration did not run");
        });
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("verso-qa-db");
        pool.setJdbcUrl(properties.url());
        pool.setUsername(properties.username());
        pool.setPassword(properties.password());
        pool.setMaximumPoolSize(properties.maximumPoolSize());
        pool.setMinimumIdle(properties.minimumIdle());
        pool.setConnectionTimeout(properties.connectionTimeout().toMillis());
        pool.setKeepaliveTime(properties.keepaliveTime().toMillis());
        // Start without a connection: a database that is down at the first use then fails with an SQLException
        // (CannotGetJdbcConnectionException, 503 SERVICE_UNAVAILABLE), not with Hikari's PoolInitializationException,
        // which no handler classifies (500).
        pool.setInitializationFailTimeout(-1);
        registry.ifAvailable(r -> pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(r)));
        return new QaDatabase(pool);
    }
}
