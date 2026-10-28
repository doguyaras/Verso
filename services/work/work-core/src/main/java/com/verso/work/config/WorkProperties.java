package com.verso.work.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Skill library settings (ADR-0057); every number is a starting value (reference 1.4), measured later.
 *
 * @param modelConcurrency    runs that may hold a model slot at once on one instance (bulkhead; the local model serves
 *                            one generation at a time, like the question path's chat slots)
 * @param modelWait           how long a new run waits for a slot before 503 MODEL_BUSY
 * @param callTimeout         one model call's bound; a run makes 1-4 step calls and at most one repair
 * @param failureThreshold    failed calls in a row that open the circuit
 * @param openFor             how long an open circuit refuses at once
 * @param retentionDays       runs (with their inputs) are deleted this many days after their creation; the same KVKK
 *                            storage limit as the conversations (VERSO_HISTORY_RETENTION_DAYS)
 * @param retentionBatch      rows per retention statement
 * @param searchTopK          passages searched per query before the documents/collections/scope filter
 * @param streamPoll          how often the progress stream re-reads the run's stage
 * @param maxStream           the longest a progress stream stays open
 */
@ConfigurationProperties("verso.work")
public record WorkProperties(
        @DefaultValue("1") int modelConcurrency,
        @DefaultValue("2s") Duration modelWait,
        @DefaultValue("180s") Duration callTimeout,
        @DefaultValue("3") int failureThreshold,
        @DefaultValue("30s") Duration openFor,
        @DefaultValue("90") int retentionDays,
        @DefaultValue("500") int retentionBatch,
        @DefaultValue("40") int searchTopK,
        @DefaultValue("700ms") Duration streamPoll,
        @DefaultValue("20m") Duration maxStream) {

    public WorkProperties {
        if (modelConcurrency < 1 || modelConcurrency > 16) throw new IllegalStateException("verso.work.model-concurrency must be 1..16");
        if (modelWait.isNegative() || modelWait.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException("verso.work.model-wait must be 0..30s");
        }
        if (!callTimeout.isPositive() || callTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalStateException("verso.work.call-timeout must be 1ms..10m");
        }
        if (failureThreshold < 1) throw new IllegalStateException("verso.work.failure-threshold must be >= 1");
        if (retentionDays < 1 || retentionDays > 3650) throw new IllegalStateException("verso.work.retention-days must be 1..3650");
        if (retentionBatch < 1 || retentionBatch > 10_000) throw new IllegalStateException("verso.work.retention-batch must be 1..10000");
        if (searchTopK < 12 || searchTopK > 100) throw new IllegalStateException("verso.work.search-top-k must be 12..100");
        if (!streamPoll.isPositive() || !maxStream.isPositive()) throw new IllegalStateException("verso.work stream durations must be positive");
    }

    /**
     * A RUNNING run whose lease passed was lost with its instance: every stage renews the lease for one call plus a
     * minute, so a live run never expires.
     */
    public Duration lease() {
        return callTimeout.plusMinutes(1);
    }
}
