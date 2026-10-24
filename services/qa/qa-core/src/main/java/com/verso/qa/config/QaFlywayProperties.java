package com.verso.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Flyway of the schema "qa" (ADR-0023), next to Boot's own Flyway of "document" (reference 10.2: one folder and one
 * configuration per module schema). Runs as svc_qa_migrate, the schema owner. On/off follows spring.flyway.enabled:
 * the long-running application (compose verso-app) migrates neither schema; the one-shot migrate container does both.
 *
 * @param url      same database, the migration role
 * @param user     svc_qa_migrate (owner of "qa", DDL)
 * @param password from /run/secrets (SECRET_DB_QA_MIGRATE_PASSWORD), no fallback; only the migrate container has it
 */
@ConfigurationProperties("verso.qa.flyway")
public record QaFlywayProperties(
        String url,
        String user,
        String password,
        @DefaultValue("classpath:db/migration/qa") String locations,
        @DefaultValue("qa") String schema) {

    public QaFlywayProperties {
        if (url == null || url.isBlank()) throw new IllegalStateException("verso.qa.flyway.url is required");
        if (user == null || user.isBlank()) throw new IllegalStateException("verso.qa.flyway.user is required");
        if (password == null || password.isEmpty()) throw new IllegalStateException("verso.qa.flyway.password is required");
    }

    /** Never the password (reference 8.4). */
    @Override
    public String toString() {
        return "QaFlywayProperties[url=" + url + ", user=" + user + ", locations=" + locations + ", schema=" + schema + "]";
    }
}
