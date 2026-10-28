package com.verso.work.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway of the schema "work" (ADR-0057). Not limited to the web application: the one-shot migrate container runs it
 * next to the other module schemas. Off with spring.flyway.enabled=false like Boot's: the long-running application holds
 * no migration password.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "spring.flyway.enabled", matchIfMissing = true)
@EnableConfigurationProperties(WorkFlywayProperties.class)
public class WorkMigrationConfiguration {

    /** Migrated while the context starts, before the work pool is first used (WorkPersistenceConfiguration waits). */
    @Bean
    WorkSchemaMigration workSchemaMigration(WorkFlywayProperties properties) {
        WorkSchemaMigration migration = new WorkSchemaMigration(properties);
        migration.migrate();
        return migration;
    }
}
