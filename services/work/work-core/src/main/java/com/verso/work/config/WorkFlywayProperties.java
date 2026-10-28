package com.verso.work.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Flyway of the schema "work" (ADR-0057), next to the other module schemas (reference 10.2: one folder and one
 * configuration per module schema). Runs as svc_work_migrate, the schema owner. On/off follows spring.flyway.enabled:
 * the long-running application migrates no schema; the one-shot migrate container does all.
 *
 * @param password from /run/secrets (SECRET_DB_WORK_MIGRATE_PASSWORD), no fallback; only the migrate container has it
 */
@ConfigurationProperties("verso.work.flyway")
public record WorkFlywayProperties(
        String url,
        String user,
        String password,
        @DefaultValue("classpath:db/migration/work") String locations,
        @DefaultValue("work") String schema) {

    public WorkFlywayProperties {
        if (url == null || url.isBlank()) throw new IllegalStateException("verso.work.flyway.url is required");
        if (user == null || user.isBlank()) throw new IllegalStateException("verso.work.flyway.user is required");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("verso.work.flyway.password is required");
        }
    }

    /** Never the password (reference 8.4). */
    @Override
    public String toString() {
        return "WorkFlywayProperties[url=" + url + ", user=" + user + ", locations=" + locations + ", schema=" + schema + "]";
    }
}
