package com.verso.qa.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway of the schema "qa" (ADR-0023). Not limited to the web application: the one-shot migrate container (no web
 * server, no application DataSource; compose "migrate") runs it next to Boot's Flyway of "document". Off with
 * spring.flyway.enabled=false like Boot's: the long-running application holds neither migration password.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "spring.flyway.enabled", matchIfMissing = true)
@EnableConfigurationProperties(QaFlywayProperties.class)
public class QaMigrationConfiguration {

    /** Migrated while the context starts, before the qa pool is first used (QaPersistenceConfiguration waits for it). */
    @Bean
    QaSchemaMigration qaSchemaMigration(QaFlywayProperties properties) {
        QaSchemaMigration migration = new QaSchemaMigration(properties);
        migration.migrate();
        return migration;
    }
}
