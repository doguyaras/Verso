package com.verso.audit.worker;

import com.verso.audit.config.AuditProperties;
import com.verso.audit.repository.AuditDatabase;
import com.verso.audit.repository.AuditEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Audit retention (ADR-0026; KVKK storage limitation): rows older than {@code verso.audit.retention-days} (default 365)
 * are removed through {@code audit.purge_expired}, the only way the application role can delete anything. The function
 * refuses a cutoff younger than 30 days, so even a compromised application cannot wipe recent history.
 *
 * <p>Multi-instance (reference 11.1): every instance schedules it; each batch runs in its own short transaction under
 * {@code pg_try_advisory_xact_lock}, so only one instance deletes at a time and the others skip that round. A delete is
 * idempotent: a batch that loses a race deletes fewer rows, never wrong ones. Restoring a backup brings expired rows
 * back; the next run removes them again.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);
    /** Bounds one run; what is left is deleted in the next one. */
    private static final int MAX_BATCHES = 200;

    /** What one run deleted; {@code skipped} when another instance held the lock. */
    public record Result(int deleted, boolean skipped) {
    }

    private final AuditDatabase db;
    private final AuditEventRepository repository;
    private final AuditProperties properties;
    private final Clock clock;

    public AuditRetentionJob(AuditDatabase db, AuditEventRepository repository, AuditProperties properties, Clock clock) {
        this.db = db;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** One failure must not stop the next run (reference 23.4); a run that deleted nothing is not logged. */
    @Scheduled(fixedDelayString = "${verso.audit.purge-interval:PT6H}",
            initialDelayString = "${verso.audit.purge-initial-delay:PT10M}")
    public void poll() {
        if (!properties.purgeEnabled()) return;
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Audit retention failed: exceptionType={}", e.getClass().getSimpleName());
        }
    }

    public Result runOnce() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(properties.retentionDays()));
        int batch = properties.purgeBatchSize();
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            Integer deleted = db.inTransaction(status ->
                    repository.tryLockRetention() ? repository.purgeExpired(cutoff, batch) : -1);
            if (deleted < 0) {
                log.info("Audit retention: outcome=skipped reason=LOCKED_BY_ANOTHER_INSTANCE");
                return new Result(total, total == 0);
            }
            total += deleted;
            if (deleted < batch) break;
        }
        if (total > 0) {
            log.info("Audit retention: deleted={} retentionDays={} outcome=deleted", total, properties.retentionDays());
        }
        return new Result(total, false);
    }
}
