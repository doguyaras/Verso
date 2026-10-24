package com.verso.document.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway of the schema "document" (ADR-0039), like qa's and audit's. Not limited to the web application: the one-shot
 * migrate container (no web server, no application pool; compose "migrate") runs it next to the qa and audit ones.
 * Off with spring.flyway.enabled=false: the long-running application holds no migration password.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "spring.flyway.enabled", matchIfMissing = true)
@EnableConfigurationProperties(DocumentFlywayProperties.class)
public class DocumentMigrationConfiguration {

    /** Migrated while the context starts, before the document pool is first used (DocumentPersistenceConfiguration). */
    @Bean
    DocumentSchemaMigration documentSchemaMigration(DocumentFlywayProperties properties) {
        DocumentSchemaMigration migration = new DocumentSchemaMigration(properties);
        migration.migrate();
        return migration;
    }
}
