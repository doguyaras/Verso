package com.verso.audit.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway of the schema "audit" (ADR-0026). Not limited to the web application: the one-shot migrate container (no web
 * server, no application DataSource; compose "migrate") runs it next to the document and qa ones. Off with
 * spring.flyway.enabled=false like Boot's: the long-running application holds no migration password.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "spring.flyway.enabled", matchIfMissing = true)
@EnableConfigurationProperties(AuditFlywayProperties.class)
public class AuditMigrationConfiguration {

    /** Migrated while the context starts, before the audit pool is first used (AuditPersistenceConfiguration waits). */
    @Bean
    AuditSchemaMigration auditSchemaMigration(AuditFlywayProperties properties) {
        AuditSchemaMigration migration = new AuditSchemaMigration(properties);
        migration.migrate();
        return migration;
    }
}
