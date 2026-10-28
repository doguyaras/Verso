package com.verso.qa.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The answer memory and its golden-set check (ADR-0049). Starting values (reference 1.4): the match threshold is
 * deliberately high until the per-customer golden sets give a measured false-hit rate.
 *
 * @param enabled             serve verified answer cards before retrieval; off: cards can still be created, verified
 *                            and evaluated, but no question is answered from them
 * @param matchThreshold      cosine similarity at or above which a new question is "the same question" as a card's
 * @param matchCandidates     nearest VERIFIED cards checked per question (each needs the caller's access to all its
 *                            cited documents); fewer than this many only means a fallback to the normal path
 * @param verificationCadence how long a verification holds; then the card goes STALE (EXPIRED) and needs a new one
 * @param twoPersonRule       a card becomes VERIFIED only after two different admins verified it
 * @param disputeThreshold    distinct users whose down rating of a served answer makes the card DISPUTED (at least 2,
 *                            so one person alone cannot pull a verified answer; default 3, the k of ADR-0023)
 * @param archivedRetention   archived cards (with their bindings, disputes and golden case) are deleted this long
 *                            after their last change
 * @param eval                the nightly golden-set check
 */
@ConfigurationProperties("verso.qa.knowledge")
public record QaKnowledgeProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0.95") double matchThreshold,
        @DefaultValue("3") int matchCandidates,
        @DefaultValue("90d") Duration verificationCadence,
        @DefaultValue("false") boolean twoPersonRule,
        @DefaultValue("3") int disputeThreshold,
        @DefaultValue("365d") Duration archivedRetention,
        @DefaultValue Eval eval) {

    public QaKnowledgeProperties {
        if (matchThreshold < 0.80 || matchThreshold > 1) {
            throw new IllegalStateException("verso.qa.knowledge.match-threshold must be 0.80..1");
        }
        if (matchCandidates < 1 || matchCandidates > 10) {
            throw new IllegalStateException("verso.qa.knowledge.match-candidates must be 1..10");
        }
        if (verificationCadence.compareTo(Duration.ofDays(1)) < 0 || verificationCadence.compareTo(Duration.ofDays(730)) > 0) {
            throw new IllegalStateException("verso.qa.knowledge.verification-cadence must be 1d..730d");
        }
        if (disputeThreshold < 2 || disputeThreshold > 100) {
            throw new IllegalStateException("verso.qa.knowledge.dispute-threshold must be 2..100");
        }
        if (archivedRetention.compareTo(Duration.ofDays(30)) < 0) {
            throw new IllegalStateException("verso.qa.knowledge.archived-retention must be at least 30d");
        }
    }

    /** Verifications a card needs in one cycle. */
    public int requiredVerifiers() {
        return twoPersonRule ? 2 : 1;
    }

    /**
     * @param enabled           run the scheduled check (a manual run is always possible)
     * @param cron              when (Spring cron, in {@code zone}); default 02:30 every night
     * @param zone              the cron's time zone
     * @param topK              the k of recall@k (the answer path's own top-k by default)
     * @param maxCases          cases per run, oldest export first; the rest waits for the next night
     * @param alertDrop         alert when recall@k falls by more than this against the previous successful run
     * @param alertSourceDrift  alert when more than this share of the cases lost a bound chunk
     * @param lease             how long a running check holds the lock before another instance may take it over
     * @param runRetention      runs (with their case results) older than this are deleted
     */
    public record Eval(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 30 2 * * *") String cron,
            @DefaultValue("Europe/Istanbul") String zone,
            @DefaultValue("5") int topK,
            @DefaultValue("1000") int maxCases,
            @DefaultValue("0.02") double alertDrop,
            @DefaultValue("0.10") double alertSourceDrift,
            @DefaultValue("1h") Duration lease,
            @DefaultValue("180d") Duration runRetention) {

        public Eval {
            if (topK < 1 || topK > 50) throw new IllegalStateException("verso.qa.knowledge.eval.top-k must be 1..50");
            if (maxCases < 1 || maxCases > 10_000) {
                throw new IllegalStateException("verso.qa.knowledge.eval.max-cases must be 1..10000");
            }
            if (alertDrop <= 0 || alertDrop >= 1 || alertSourceDrift <= 0 || alertSourceDrift >= 1) {
                throw new IllegalStateException("verso.qa.knowledge.eval.alert-* must be between 0 and 1");
            }
            if (!lease.isPositive()) throw new IllegalStateException("verso.qa.knowledge.eval.lease must be positive");
            if (runRetention.compareTo(Duration.ofDays(7)) < 0) {
                throw new IllegalStateException("verso.qa.knowledge.eval.run-retention must be at least 7d");
            }
        }
    }
}
