package com.verso.work.config;

import com.verso.work.repository.WorkDatabase;
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
 * The work module's own pool on the schema "work" (ADR-0057), connected as svc_work. Web application only: the
 * one-shot migrate run has no application connections. Same decision as qa, audit and connector (ADR-0023 option A):
 * one pool and one role per module schema.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({WorkDatabaseProperties.class, WorkProperties.class})
@EnableScheduling
public class WorkPersistenceConfiguration {

    /** Lazy like Boot's pool; waits for the work migration when this context runs one (IDE, tests). */
    @Bean(destroyMethod = "close")
    WorkDatabase workDatabase(WorkDatabaseProperties properties, ObjectProvider<WorkSchemaMigration> migration,
                              ObjectProvider<MeterRegistry> registry) {
        migration.ifAvailable(m -> {
            if (!m.migrated()) throw new IllegalStateException("work schema migration did not run");
        });
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("verso-work-db");
        pool.setJdbcUrl(properties.url());
        pool.setUsername(properties.username());
        pool.setPassword(properties.password());
        pool.setMaximumPoolSize(properties.maximumPoolSize());
        pool.setMinimumIdle(properties.minimumIdle());
        pool.setConnectionTimeout(properties.connectionTimeout().toMillis());
        pool.setKeepaliveTime(properties.keepaliveTime().toMillis());
        // A database that is down at start-up fails the first statement (503), not the application start.
        pool.setInitializationFailTimeout(-1);
        registry.ifAvailable(r -> pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(r)));
        return new WorkDatabase(pool);
    }
}
