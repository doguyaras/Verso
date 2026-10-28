package com.verso.work.worker;

import com.verso.work.service.RunMaintenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The skill library's two sweeps (ADR-0057), on every instance (SKIP LOCKED shares the rows): a run whose instance died
 * is failed as INTERRUPTED once its lease passes (every minute), and runs older than the retention are deleted with their
 * inputs (every hour). One failure must not stop the next run; the log carries counts and the exception type only.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RunSweepJob {

    private static final Logger log = LoggerFactory.getLogger(RunSweepJob.class);

    private final RunMaintenance maintenance;

    public RunSweepJob(RunMaintenance maintenance) {
        this.maintenance = maintenance;
    }

    @Scheduled(fixedDelayString = "${verso.work.sweep-interval:1m}", initialDelayString = "${verso.work.sweep-interval:1m}")
    public void lostRuns() {
        try {
            int failed = maintenance.failLostRuns();
            if (failed > 0) log.warn("Lost skill runs failed: count={} reason=INTERRUPTED", failed);
        } catch (RuntimeException e) {
            log.error("Skill run sweep failed: exceptionType={}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${verso.work.retention-interval:1h}",
            initialDelayString = "${verso.work.retention-interval:1h}")
    public void retention() {
        try {
            long deleted = maintenance.deleteExpired();
            if (deleted > 0) log.info("Skill run retention: deleted={}", deleted);
        } catch (RuntimeException e) {
            log.error("Skill run retention failed: exceptionType={}", e.getClass().getSimpleName());
        }
    }
}
