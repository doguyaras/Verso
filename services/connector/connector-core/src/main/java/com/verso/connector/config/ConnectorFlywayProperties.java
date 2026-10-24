package com.verso.connector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Flyway of the schema "connector" (ADR-0038), next to the document and qa ones (reference 10.2: one folder and one
 * configuration per module schema). Runs as svc_connector_migrate, the schema owner. On/off follows spring.flyway.enabled:
 * the long-running application (compose verso-app) migrates no schema; the one-shot migrate container does all.
 *
 * @param url      same database, the migration role
 * @param user     svc_connector_migrate (owner of "connector", DDL)
 * @param password from /run/secrets (SECRET_DB_CONNECTOR_MIGRATE_PASSWORD), no fallback; only the migrate container has it
 */
@ConfigurationProperties("verso.connector.flyway")
public record ConnectorFlywayProperties(
        String url,
        String user,
        String password,
        @DefaultValue("classpath:db/migration/connector") String locations,
        @DefaultValue("connector") String schema) {

    public ConnectorFlywayProperties {
        if (url == null || url.isBlank()) throw new IllegalStateException("verso.connector.flyway.url is required");
        if (user == null || user.isBlank()) throw new IllegalStateException("verso.connector.flyway.user is required");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("verso.connector.flyway.password is required");
        }
    }

    /** Never the password (reference 8.4). */
    @Override
    public String toString() {
        return "ConnectorFlywayProperties[url=" + url + ", user=" + user + ", locations=" + locations + ", schema=" + schema + "]";
    }
}
