package com.verso.work.service;

import com.verso.work.config.WorkProperties;
import com.verso.work.outbox.WorkAudit;
import com.verso.work.repository.SkillRunRepository;
import com.verso.work.repository.WorkDatabase;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/** The sweeps of {@code RunSweepJob}: lost runs and retention (ADR-0057). */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RunMaintenance {

    private static final int LOST_BATCH = 100;
    private static final int MAX_BATCHES = 100;

    private final SkillRunRepository runs;
    private final WorkDatabase db;
    private final WorkAudit audit;
    private final WorkProperties properties;
    private final Clock clock;

    public RunMaintenance(SkillRunRepository runs, WorkDatabase db, WorkAudit audit, WorkProperties properties,
                          Clock clock) {
        this.runs = runs;
        this.db = db;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Fails the RUNNING runs whose lease passed, each with its audit row, in one transaction per batch. */
    public int failLostRuns() {
        return db.transactionally(() -> {
            List<UUID> lost = runs.failLost(LOST_BATCH);
            lost.forEach(id -> audit.finished(id, "INTERRUPTED", null, List.of()));
            return lost.size();
        });
    }

    /** Deletes runs older than the retention, batch by batch; returns how many. */
    public long deleteExpired() {
        var cutoff = clock.instant().minus(Duration.ofDays(properties.retentionDays()));
        long total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int deleted = db.transactionally(() -> runs.deleteCreatedBefore(cutoff, properties.retentionBatch()));
            total += deleted;
            if (deleted < properties.retentionBatch()) break;
        }
        return total;
    }
}
