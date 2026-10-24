package com.verso.audit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Flyway of the schema "audit" (ADR-0026), next to the document and qa ones (reference 10.2: one folder and one
 * configuration per module schema). Runs as svc_audit_migrate, the schema owner. On/off follows spring.flyway.enabled:
 * the long-running application (compose verso-app) migrates no schema; the one-shot migrate container does all.
 *
 * @param url      same database, the migration role
 * @param user     svc_audit_migrate (owner of "audit", DDL)
 * @param password from /run/secrets (SECRET_DB_AUDIT_MIGRATE_PASSWORD), no fallback; only the migrate container has it
 */
@ConfigurationProperties("verso.audit.flyway")
public record AuditFlywayProperties(
        String url,
        String user,
        String password,
        @DefaultValue("classpath:db/migration/audit") String locations,
        @DefaultValue("audit") String schema) {

    public AuditFlywayProperties {
        if (url == null || url.isBlank()) throw new IllegalStateException("verso.audit.flyway.url is required");
        if (user == null || user.isBlank()) throw new IllegalStateException("verso.audit.flyway.user is required");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("verso.audit.flyway.password is required");
        }
    }

    /** Never the password (reference 8.4). */
    @Override
    public String toString() {
        return "AuditFlywayProperties[url=" + url + ", user=" + user + ", locations=" + locations + ", schema=" + schema + "]";
    }
}
