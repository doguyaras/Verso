package com.verso.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Flyway of the schema "document" (ADR-0039), next to the qa and audit ones (reference 10.2: one folder and one
 * configuration per module schema). Replaces Boot's spring.flyway.* connection keys. Runs as svc_document_migrate,
 * the schema owner. On/off follows spring.flyway.enabled, the switch of every module migration: the long-running
 * application (compose verso-app) migrates no schema; the one-shot migrate container does all.
 *
 * @param url      same database, the migration role
 * @param user     svc_document_migrate (owner of "document", DDL)
 * @param password from /run/secrets (SECRET_DB_DOCUMENT_MIGRATE_PASSWORD), no fallback; only the migrate container has it
 */
@ConfigurationProperties("verso.document.flyway")
public record DocumentFlywayProperties(
        String url,
        String user,
        String password,
        @DefaultValue("classpath:db/migration/document") String locations,
        @DefaultValue("document") String schema) {

    public DocumentFlywayProperties {
        if (url == null || url.isBlank()) throw new IllegalStateException("verso.document.flyway.url is required");
        if (user == null || user.isBlank()) throw new IllegalStateException("verso.document.flyway.user is required");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("verso.document.flyway.password is required");
        }
    }

    /** Never the password (reference 8.4). */
    @Override
    public String toString() {
        return "DocumentFlywayProperties[url=" + url + ", user=" + user + ", locations=" + locations + ", schema="
                + schema + "]";
    }
}
