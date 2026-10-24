package com.verso.audit.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The audit module's own connection (ADR-0026): the application role svc_audit of the schema "audit" (reference 10.1).
 * It can INSERT and SELECT on the event table and nothing else; never the application DataSource.
 *
 * @param url               same database as the other modules, its own role
 * @param username          svc_audit (INSERT and SELECT on audit.event, no DDL)
 * @param password          from /run/secrets (SECRET_DB_AUDIT_PASSWORD), no fallback
 * @param maximumPoolSize   small on purpose: one short insert per audited operation, admin reads
 * @param minimumIdle       connections kept open while idle
 * @param connectionTimeout short on purpose: the row is written inside the request, after the operation committed; an
 *                          unreachable audit database may delay a request this long at most (then the breaker opens)
 */
@ConfigurationProperties("verso.audit.datasource")
public record AuditDatabaseProperties(
        String url,
        String username,
        String password,
        @DefaultValue("4") int maximumPoolSize,
        @DefaultValue("1") int minimumIdle,
        @DefaultValue("1s") Duration connectionTimeout,
        @DefaultValue("2m") Duration keepaliveTime) {

    public AuditDatabaseProperties {
        if (url == null || url.isBlank()) throw new IllegalStateException("verso.audit.datasource.url is required");
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("verso.audit.datasource.username is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("verso.audit.datasource.password is required");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 20) {
            throw new IllegalStateException("verso.audit.datasource.maximum-pool-size must be 1..20");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalStateException("verso.audit.datasource.minimum-idle must be 0..maximum-pool-size");
        }
        if (!connectionTimeout.isPositive() || connectionTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalStateException("verso.audit.datasource.connection-timeout must be 1ms..5s");
        }
    }

    /** Never the password (reference 8.4). */
    @Override
    public String toString() {
        return "AuditDatabaseProperties[url=" + url + ", username=" + username + ", maximumPoolSize=" + maximumPoolSize + "]";
    }
}
