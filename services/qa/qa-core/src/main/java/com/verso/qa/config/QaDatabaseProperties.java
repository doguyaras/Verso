package com.verso.qa.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The qa module's own connection (ADR-0023): the application role svc_qa of the schema "qa" (reference 10.1). Never
 * the application DataSource (svc_document): that role has no right on this schema and must not get one.
 *
 * @param url             same database as the document module, its own role
 * @param username        svc_qa (DML only, no DDL)
 * @param password        from /run/secrets (SECRET_DB_QA_PASSWORD), no fallback
 * @param maximumPoolSize small on purpose: one short write per answer, a few reads (connection budget, ADR-0023)
 * @param minimumIdle     connections kept open while idle
 * @param connectionTimeout short on purpose: the turn is stored after the answer exists, and a busy pool must not
 *                          hold the answer back for long (a lost turn is counted, the answer is still returned)
 */
@ConfigurationProperties("verso.qa.datasource")
public record QaDatabaseProperties(
        String url,
        String username,
        String password,
        @DefaultValue("4") int maximumPoolSize,
        @DefaultValue("1") int minimumIdle,
        @DefaultValue("2s") Duration connectionTimeout,
        @DefaultValue("2m") Duration keepaliveTime) {

    public QaDatabaseProperties {
        if (url == null || url.isBlank()) throw new IllegalStateException("verso.qa.datasource.url is required");
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("verso.qa.datasource.username is required");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("verso.qa.datasource.password is required");
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 20) {
            throw new IllegalStateException("verso.qa.datasource.maximum-pool-size must be 1..20");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalStateException("verso.qa.datasource.minimum-idle must be 0..maximum-pool-size");
        }
    }

    /** Never the password (reference 8.4). */
    @Override
    public String toString() {
        return "QaDatabaseProperties[url=" + url + ", username=" + username + ", maximumPoolSize=" + maximumPoolSize + "]";
    }
}
