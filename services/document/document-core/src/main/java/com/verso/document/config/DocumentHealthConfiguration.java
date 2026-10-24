package com.verso.document.config;

import com.verso.document.config.DocumentPersistenceConfiguration.DocumentDataSource;
import java.sql.Connection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * "db" in the readiness group (application.yml, ADR-0039): the document pool, next to qaDb. Declared here instead of
 * Boot's DataSource health contributor, which exists only when a DataSource bean does: the group is validated at
 * start-up in every application type, and a context without the document pool (the migrate run) must still start.
 * There it reports UP (no pool, no request to lose). The bean name keeps Boot's own contributor away
 * (dbHealthIndicator). The failure detail is the exception type only.
 */
@Configuration(proxyBeanMethods = false)
public class DocumentHealthConfiguration {

    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    @Bean
    HealthIndicator dbHealthIndicator(ObjectProvider<DocumentDataSource> dataSource) {
        return () -> {
            DocumentDataSource pool = dataSource.getIfAvailable();
            if (pool == null) return Health.up().build();
            try (Connection connection = pool.getConnection()) {
                return connection.isValid(VALIDATION_TIMEOUT_SECONDS) ? Health.up().build()
                        : Health.down().withDetail("exceptionType", "ConnectionNotValid").build();
            } catch (Exception e) {
                return Health.down().withDetail("exceptionType", e.getClass().getSimpleName()).build();
            }
        };
    }
}
