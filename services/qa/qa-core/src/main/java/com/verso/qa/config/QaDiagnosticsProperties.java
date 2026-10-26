package com.verso.qa.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The company diagnostics ("bilgi sağlığı") and the hooks of the learning knowledge layer (ADR-0052). Starting values
 * (reference 1.4) except {@code minAskers}, whose floor of 5 is a guarantee (ADR-0048 k reconciliation): configuration
 * may raise it, never lower it (the database refuses a cluster below 5 as well).
 *
 * @param enabled                run the nightly job (a manual run is always possible)
 * @param cron                   when (Spring cron, in {@code zone}); default 03:30, after the golden-set check
 * @param zone                   the cron's time zone
 * @param window                 the question window of a run, ending yesterday
 * @param minAskers              k: distinct accounts per cluster, keyword, representative question, stale document and
 *                               feedback cell; at least 5
 * @param clusterThreshold       cosine similarity at which a question joins a cluster
 * @param trendThreshold         cosine similarity of two centroids that makes them "the same cluster" across runs
 * @param maxQuestions           uncertain questions clustered per run (newest first); the rest are left out
 * @param embedPerRun            questions embedded per run (local model, offline); the rest wait for the next run
 * @param keywords               keywords per cluster
 * @param representatives        representative questions per cluster (shown only under the question-text policy)
 * @param staleAfter             a shared document older than this, yet cited, is "stale"
 * @param verificationDueWithin  a VERIFIED card whose verification ends within this gets a VERIFICATION_DUE hook
 * @param lease                  how long a running job holds the lock before another instance may take it over
 * @param runRetention           runs (with their clusters) older than this are deleted: keep a year for quarterly reports
 * @param notificationRetention  hook notifications older than this are deleted
 * @param contradictionRetention contradiction candidates not seen again for this long are deleted
 * @param maxRangeDays           longest from..to range of one report request
 * @param evalDebounce           a golden-set check asked by a hook runs after this quiet period (coalesced)
 * @param contradiction          the offline contradiction scan
 */
@ConfigurationProperties("verso.qa.diagnostics")
public record QaDiagnosticsProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 30 3 * * *") String cron,
        @DefaultValue("Europe/Istanbul") String zone,
        @DefaultValue("30d") Duration window,
        @DefaultValue("5") int minAskers,
        @DefaultValue("0.80") double clusterThreshold,
        @DefaultValue("0.85") double trendThreshold,
        @DefaultValue("5000") int maxQuestions,
        @DefaultValue("2000") int embedPerRun,
        @DefaultValue("5") int keywords,
        @DefaultValue("3") int representatives,
        @DefaultValue("180d") Duration staleAfter,
        @DefaultValue("14d") Duration verificationDueWithin,
        @DefaultValue("1h") Duration lease,
        @DefaultValue("400d") Duration runRetention,
        @DefaultValue("180d") Duration notificationRetention,
        @DefaultValue("30d") Duration contradictionRetention,
        @DefaultValue("400") int maxRangeDays,
        @DefaultValue("10m") Duration evalDebounce,
        @DefaultValue Contradiction contradiction) {

    /** The k floor of multi-dimensional diagnostics (ADR-0048). */
    public static final int MIN_ASKERS_FLOOR = 5;

    public QaDiagnosticsProperties {
        try {
            ZoneId.of(zone);
        } catch (RuntimeException e) {
            throw new IllegalStateException("verso.qa.diagnostics.zone is not a time zone id");
        }
        if (window.compareTo(Duration.ofDays(1)) < 0 || window.compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalStateException("verso.qa.diagnostics.window must be 1d..366d");
        }
        if (minAskers < MIN_ASKERS_FLOOR || minAskers > 1000) {
            throw new IllegalStateException("verso.qa.diagnostics.min-askers must be " + MIN_ASKERS_FLOOR + "..1000");
        }
        if (clusterThreshold < 0.5 || clusterThreshold >= 1 || trendThreshold < 0.5 || trendThreshold >= 1) {
            throw new IllegalStateException("verso.qa.diagnostics.*-threshold must be 0.5..1 (exclusive)");
        }
        if (maxQuestions < 1 || maxQuestions > 20_000 || embedPerRun < 0 || embedPerRun > 20_000) {
            throw new IllegalStateException("verso.qa.diagnostics.max-questions / embed-per-run out of range");
        }
        if (keywords < 0 || keywords > 10 || representatives < 0 || representatives > 5) {
            throw new IllegalStateException("verso.qa.diagnostics.keywords must be 0..10, representatives 0..5");
        }
        if (!staleAfter.isPositive() || !verificationDueWithin.isPositive() || !lease.isPositive()
                || !evalDebounce.isPositive()) {
            throw new IllegalStateException("verso.qa.diagnostics durations must be positive");
        }
        if (runRetention.compareTo(Duration.ofDays(7)) < 0 || notificationRetention.compareTo(Duration.ofDays(7)) < 0
                || contradictionRetention.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalStateException("verso.qa.diagnostics retentions are too short");
        }
        if (maxRangeDays < 1 || maxRangeDays > 3660) {
            throw new IllegalStateException("verso.qa.diagnostics.max-range-days must be 1..3660");
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    /**
     * @param enabled     run the scan in the nightly job
     * @param similarity  cosine similarity at which two chunks of different documents are compared
     * @param maxChunks   chunks (with a digit) scanned per run; the scan continues from its cursor the next run
     * @param neighbours  nearest chunks of other documents compared per scanned chunk
     * @param maxPairs    new candidates stored per run
     */
    public record Contradiction(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.90") double similarity,
            @DefaultValue("2000") int maxChunks,
            @DefaultValue("3") int neighbours,
            @DefaultValue("50") int maxPairs) {

        public Contradiction {
            if (similarity < 0.5 || similarity > 1) {
                throw new IllegalStateException("verso.qa.diagnostics.contradiction.similarity must be 0.5..1");
            }
            if (maxChunks < 1 || maxChunks > 10_000 || neighbours < 1 || neighbours > 10 || maxPairs < 1
                    || maxPairs > 1000) {
                throw new IllegalStateException("verso.qa.diagnostics.contradiction limits out of range");
            }
        }
    }
}
