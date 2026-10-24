package com.verso.connector.config;

import org.flywaydb.core.Flyway;

/**
 * Migrates the schema "connector" as its owner, svc_connector_migrate (ADR-0038). Deliberately not a {@link Flyway} bean: one
 * more Flyway bean would switch off Boot's auto-configured Flyway of the "document" schema (ADR-0009, review A1). The
 * same settings as there: schema created by the init scripts, no clean, no baseline-on-migrate (reference 10.2).
 */
public final class ConnectorSchemaMigration {

    private final ConnectorFlywayProperties properties;
    private volatile boolean migrated;

    public ConnectorSchemaMigration(ConnectorFlywayProperties properties) {
        this.properties = properties;
    }

    /** Runs once at start-up; Flyway takes its own lock on the history table, so concurrent starts are safe. */
    public void migrate() {
        Flyway.configure(ConnectorSchemaMigration.class.getClassLoader())
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
