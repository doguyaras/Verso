package com.verso.audit.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The audit log's policy (ADR-0026). The retention is the installation's storage-limitation setting (KVKK md. 4/2-d
 * and the security measures of md. 12): VERSO_AUDIT_RETENTION_DAYS (deploy/prod.env.example). The database enforces a
 * floor of 30 days that no caller of the retention function can go under, so a compromised application cannot erase
 * recent history by lowering this number.
 *
 * @param retentionDays    rows older than this many days are deleted (default 365); 30..3650
 * @param purgeEnabled     the scheduled retention job (tests drive it themselves)
 * @param purgeInterval    how often the job runs; every instance runs it, one at a time (advisory lock)
 * @param purgeInitialDelay first run after start-up
 * @param purgeBatchSize   rows per delete call, each in its own short transaction
 * @param exportMaxRows    one CSV export carries at most this many rows; the response says when it was cut
 * @param exportBatchSize  rows per read while streaming the export (no long transaction, no cursor)
 * @param maxRangeDays     the widest from..to window a query or export accepts
 * @param timeZone         zone of the calendar days {@code from} and {@code to} (inclusive); timestamps stay UTC
 * @param breakerOpen      after a failed write the audit database is not tried again for this long, so an outage costs
 *                         the requests one timeout, not one each
 */
@ConfigurationProperties("verso.audit")
public record AuditProperties(
        @DefaultValue("365") int retentionDays,
        @DefaultValue("true") boolean purgeEnabled,
        @DefaultValue("6h") Duration purgeInterval,
        @DefaultValue("10m") Duration purgeInitialDelay,
        @DefaultValue("1000") int purgeBatchSize,
        @DefaultValue("100000") int exportMaxRows,
        @DefaultValue("1000") int exportBatchSize,
        @DefaultValue("366") int maxRangeDays,
        @DefaultValue("Europe/Istanbul") ZoneId timeZone,
        @DefaultValue("10s") Duration breakerOpen) {

    /** Same value as the floor inside audit.purge_expired (V1__audit_event.sql). */
    public static final int MIN_RETENTION_DAYS = 30;

    public AuditProperties {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > 3650) {
            throw new IllegalStateException("verso.audit.retention-days must be " + MIN_RETENTION_DAYS + "..3650");
        }
        if (!purgeInterval.isPositive()) throw new IllegalStateException("verso.audit.purge-interval must be positive");
        if (purgeInitialDelay.isNegative()) {
            throw new IllegalStateException("verso.audit.purge-initial-delay must not be negative");
        }
        if (purgeBatchSize < 1 || purgeBatchSize > 5000) {
            throw new IllegalStateException("verso.audit.purge-batch-size must be 1..5000");
        }
        if (exportMaxRows < 1 || exportMaxRows > 1_000_000) {
            throw new IllegalStateException("verso.audit.export-max-rows must be 1..1000000");
        }
        if (exportBatchSize < 1 || exportBatchSize > 5000) {
            throw new IllegalStateException("verso.audit.export-batch-size must be 1..5000");
        }
        if (maxRangeDays < 1 || maxRangeDays > 3650) {
            throw new IllegalStateException("verso.audit.max-range-days must be 1..3650");
        }
        if (breakerOpen.isNegative()) throw new IllegalStateException("verso.audit.breaker-open must not be negative");
    }
}
