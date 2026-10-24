package com.verso.document.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The document module's own connection (ADR-0039, mirrors qa ADR-0023 and audit ADR-0026): the application role
 * svc_document of the schema "document" (reference 10.1), DML only. Replaces Boot's spring.datasource.*: the pool is
 * declared by this module, not auto-configured for the whole application.
 *
 * @param url               same database as the other modules, its own role
 * @param username          svc_document (DML on "document", no DDL)
 * @param password          from /run/secrets (SECRET_DB_DOCUMENT_PASSWORD), no fallback
 * @param maximumPoolSize   (cores x 2) + disks, small on purpose: fewer connections are faster (reference 10.5); uploads,
 *                          listings, retrieval and the ingestion worker share it
 * @param minimumIdle       connections kept open while idle
 * @param connectionTimeout how long a request waits for a free connection before it fails (503)
 * @param keepaliveTime     middleboxes drop long idle connections; keepalive renews them before that (reference 10.5)
 */
@ConfigurationProperties("verso.document.datasource")
public record DocumentDatabaseProperties(
        String url,
        String username,
        String password,
        @DefaultValue("10") int maximumPoolSize,
        @DefaultValue("10") int minimumIdle,
        @DefaultValue("5s") Duration connectionTimeout,
        @DefaultValue("2m") Duration keepaliveTime) {

    public DocumentDatabaseProperties {
        if (url == null || url.isBlank()) throw new IllegalStateException("verso.document.datasource.url is required");
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("verso.document.datasource.username is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("verso.document.datasource.password is required");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 50) {
            throw new IllegalStateException("verso.document.datasource.maximum-pool-size must be 1..50");
        }
        if (minimumIdle < 0) throw new IllegalStateException("verso.document.datasource.minimum-idle must be >= 0");
        // Hikari's own rule, as under Boot's spring.datasource.hikari: a smaller pool lowers the idle count with it.
        minimumIdle = Math.min(minimumIdle, maximumPoolSize);
        if (!connectionTimeout.isPositive() || connectionTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException("verso.document.datasource.connection-timeout must be 1ms..30s");
        }
    }

    /** Never the password (reference 8.4). */
    @Override
    public String toString() {
        return "DocumentDatabaseProperties[url=" + url + ", username=" + username + ", maximumPoolSize="
                + maximumPoolSize + "]";
    }
}
