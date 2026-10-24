package com.verso.connector.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The connector module's own connection (ADR-0038): the application role svc_connector of the schema "connector"
 * (reference 10.1): DML on its own tables, no DDL, nothing on another schema; never the application DataSource.
 *
 * @param url               same database as the other modules, its own role
 * @param username          svc_connector (DML on the connector schema, no DDL)
 * @param password          from /run/secrets (SECRET_DB_CONNECTOR_PASSWORD), no fallback
 * @param maximumPoolSize   small on purpose: one sync worker per instance and the admin endpoints
 * @param minimumIdle       connections kept open while idle
 * @param connectionTimeout short on purpose: an unreachable connector database fails an admin request after this wait
 *                          (503 SERVICE_UNAVAILABLE) instead of holding it
 */
@ConfigurationProperties("verso.connector.datasource")
public record ConnectorDatabaseProperties(
        String url,
        String username,
        String password,
        @DefaultValue("4") int maximumPoolSize,
        @DefaultValue("1") int minimumIdle,
        @DefaultValue("2s") Duration connectionTimeout,
        @DefaultValue("2m") Duration keepaliveTime) {

    public ConnectorDatabaseProperties {
        if (url == null || url.isBlank()) throw new IllegalStateException("verso.connector.datasource.url is required");
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("verso.connector.datasource.username is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("verso.connector.datasource.password is required");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 20) {
            throw new IllegalStateException("verso.connector.datasource.maximum-pool-size must be 1..20");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalStateException("verso.connector.datasource.minimum-idle must be 0..maximum-pool-size");
        }
        if (!connectionTimeout.isPositive() || connectionTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalStateException("verso.connector.datasource.connection-timeout must be 1ms..5s");
        }
    }

    /** Never the password (reference 8.4). */
    @Override
    public String toString() {
        return "ConnectorDatabaseProperties[url=" + url + ", username=" + username + ", maximumPoolSize=" + maximumPoolSize + "]";
    }
}
