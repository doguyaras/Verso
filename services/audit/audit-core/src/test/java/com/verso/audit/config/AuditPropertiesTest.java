package com.verso.audit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** The audit settings fail closed at start-up (ADR-0026). */
class AuditPropertiesTest {

    private static AuditProperties properties(int retentionDays) {
        return new AuditProperties(retentionDays, true, Duration.ofHours(6), Duration.ofMinutes(10), 1000, 100_000, 1000, 366,
                ZoneId.of("Europe/Istanbul"), Duration.ofSeconds(10));
    }

    @Test
    void retention_whenOutOfRange_doesNotStart() {
        assertThatThrownBy(() -> properties(29)).hasMessageContaining("retention-days");
        assertThatThrownBy(() -> properties(0)).hasMessageContaining("retention-days");
        assertThatThrownBy(() -> properties(3651)).hasMessageContaining("retention-days");
        assertThat(properties(30).retentionDays()).as("the floor the database enforces too").isEqualTo(30);
        assertThat(properties(365).retentionDays()).isEqualTo(365);
    }

    @Test
    void limits_whenOutOfRange_doNotStart() {
        assertThatThrownBy(() -> new AuditProperties(365, true, Duration.ofHours(6), Duration.ofMinutes(10), 5001, 100, 10, 366,
                ZoneId.of("UTC"), Duration.ZERO)).hasMessageContaining("purge-batch-size");
        assertThatThrownBy(() -> new AuditProperties(365, true, Duration.ofHours(6), Duration.ofMinutes(10), 10, 0, 10, 366,
                ZoneId.of("UTC"), Duration.ZERO)).hasMessageContaining("export-max-rows");
        assertThatThrownBy(() -> new AuditProperties(365, true, Duration.ZERO, Duration.ofMinutes(10), 10, 10, 10, 366,
                ZoneId.of("UTC"), Duration.ZERO)).hasMessageContaining("purge-interval");
    }

    @Test
    void database_whenPrintedOrMisconfigured_neverShowsThePasswordAndFailsFast() {
        AuditDatabaseProperties database = new AuditDatabaseProperties("jdbc:postgresql://db/verso", "svc_audit",
                "secret-value-x", 4, 1, Duration.ofSeconds(1), Duration.ofMinutes(2));
        assertThat(database.toString()).doesNotContain("secret-value-x").contains("svc_audit");
        assertThatThrownBy(() -> new AuditDatabaseProperties("jdbc:postgresql://db/verso", "svc_audit", "", 4, 1,
                Duration.ofSeconds(1), Duration.ofMinutes(2))).hasMessageContaining("password");
        assertThatThrownBy(() -> new AuditDatabaseProperties("jdbc:postgresql://db/verso", "svc_audit", "x", 4, 1,
                Duration.ofSeconds(30), Duration.ofMinutes(2))).hasMessageContaining("connection-timeout");
        AuditFlywayProperties flyway = new AuditFlywayProperties("jdbc:postgresql://db/verso", "svc_audit_migrate",
                "secret-value-y", "classpath:db/migration/audit", "audit");
        assertThat(flyway.toString()).doesNotContain("secret-value-y");
    }
}
