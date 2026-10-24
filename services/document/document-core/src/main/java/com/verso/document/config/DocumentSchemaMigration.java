package com.verso.document.config;

import org.flywaydb.core.Flyway;

/**
 * Migrates the schema "document" as its owner, svc_document_migrate (ADR-0039). Not a {@link Flyway} bean, like the qa
 * and audit ones: Boot's Flyway auto-configuration is switched off (application.yml) and each module runs its own.
 * Settings as before under spring.flyway: schema created by the init scripts, no clean, no baseline-on-migrate
 * (reference 10.2).
 */
public final class DocumentSchemaMigration {

    private final DocumentFlywayProperties properties;
    private volatile boolean migrated;

    public DocumentSchemaMigration(DocumentFlywayProperties properties) {
        this.properties = properties;
    }

    /** Runs once at start-up; Flyway takes its own lock on the history table, so concurrent starts are safe. */
    public void migrate() {
        Flyway.configure(DocumentSchemaMigration.class.getClassLoader())
                .dataSource(properties.url(), properties.user(), properties.password())
                .schemas(properties.schema())
                .defaultSchema(properties.schema())
                .createSchemas(false)
                .locations(properties.locations())
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
        migrated = true;
    }

    public boolean migrated() {
        return migrated;
    }
}
