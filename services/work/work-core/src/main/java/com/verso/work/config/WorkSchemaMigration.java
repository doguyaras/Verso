package com.verso.work.config;

import org.flywaydb.core.Flyway;

/**
 * Migrates the schema "work" as its owner, svc_work_migrate (ADR-0057). Deliberately not a {@link Flyway} bean: one more
 * Flyway bean would switch off Boot's auto-configured Flyway of the "document" schema (ADR-0009). Same settings as the
 * other module schemas: schema created by the init scripts, no clean, no baseline-on-migrate (reference 10.2).
 */
public final class WorkSchemaMigration {

    private final WorkFlywayProperties properties;
    private volatile boolean migrated;

    public WorkSchemaMigration(WorkFlywayProperties properties) {
        this.properties = properties;
    }

    /** Runs once at start-up; Flyway takes its own lock on the history table, so concurrent starts are safe. */
    public void migrate() {
        Flyway.configure(WorkSchemaMigration.class.getClassLoader())
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
