package com.verso.work.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The work module's own connection (ADR-0057): the application role svc_work of the schema "work" (reference 10.1):
 * DML on its own tables, no DDL, nothing on another schema; never the application DataSource.
 *
 * @param url               same database as the other modules, its own role
 * @param username          svc_work (DML on the work schema, no DDL)
 * @param password          from /run/secrets (SECRET_DB_WORK_PASSWORD), no fallback
 * @param maximumPoolSize   small on purpose: short statements per request and per run stage
 * @param connectionTimeout an unreachable database fails a request after this wait (503) instead of holding it
 */
@ConfigurationProperties("verso.work.datasource")
public record WorkDatabaseProperties(
        String url,
        String username,
        String password,
        @DefaultValue("4") int maximumPoolSize,
        @DefaultValue("1") int minimumIdle,
        @DefaultValue("2s") Duration connectionTimeout,
        @DefaultValue("2m") Duration keepaliveTime) {

    public WorkDatabaseProperties {
        if (url == null || url.isBlank()) throw new IllegalStateException("verso.work.datasource.url is required");
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("verso.work.datasource.username is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("verso.work.datasource.password is required");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 20) {
            throw new IllegalStateException("verso.work.datasource.maximum-pool-size must be 1..20");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalStateException("verso.work.datasource.minimum-idle must be 0..maximum-pool-size");
        }
        if (!connectionTimeout.isPositive() || connectionTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalStateException("verso.work.datasource.connection-timeout must be 1ms..5s");
        }
    }

    /** Never the password (reference 8.4). */
    @Override
    public String toString() {
        return "WorkDatabaseProperties[url=" + url + ", username=" + username + ", maximumPoolSize=" + maximumPoolSize + "]";
    }
}
