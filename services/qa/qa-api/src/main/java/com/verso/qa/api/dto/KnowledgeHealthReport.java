package com.verso.qa.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The knowledge-health report (ADR-0052): the consulting deliverable of the learning knowledge layer. Built from the
 * latest successful diagnostics run whose window ends in {@code from..to}; every aggregate is k-anonymous (at least
 * {@code minAskers} distinct accounts per cell and window, ADR-0048) and nothing names or counts one user.
 *
 * @param run                 the run the report is built from; null when no run ended in the range (every list empty)
 * @param minAskers           the k of the run
 * @param questionTextShown   the installation's question-text policy (VERSO_SHOW_UNANSWERED_QUESTIONS): when false no
 *                            question text is ever returned, only counts and keywords
 * @param findings            templated Turkish "finding + recommendation" items, most severe first
 * @param neverCitedDocuments shared documents never cited by any answer
 * @param trend               the runs ending in the range, oldest first (questions and clusters per run)
 * @param openFlags           hook items still waiting for review
 */
public record KnowledgeHealthReport(LocalDate from, LocalDate to, Instant generatedAt, Run run, int minAskers,
                                    boolean questionTextShown, List<Finding> findings, List<Cluster> clusters,
                                    List<StaleDocument> staleDocuments, List<CollectionCoverage> coverage,
                                    long neverCitedDocuments, List<Contradiction> contradictions, Cards cards,
                                    List<FeedbackCell> downRatingReasons, List<RunPoint> trend, long openFlags) {

    public KnowledgeHealthReport {
        findings = List.copyOf(findings);
        clusters = List.copyOf(clusters);
        staleDocuments = List.copyOf(staleDocuments);
        coverage = List.copyOf(coverage);
        contradictions = List.copyOf(contradictions);
        downRatingReasons = List.copyOf(downRatingReasons);
        trend = List.copyOf(trend);
    }

    /** The diagnostics run behind the report: its question window and what it found. */
    public record Run(UUID id, LocalDate windowFrom, LocalDate windowTo, Instant finishedAt, int questions,
                      int clusters, int contradictionsChecked) {
    }

    /**
     * One finding with its recommendation, in Turkish, from fixed templates (never model-written).
     *
     * @param code a stable key of the template (UNANSWERED_CLUSTER, STALE_DOCUMENT, ...)
     */
    public record Finding(String code, KnowledgeHealthSeverity severity, String title, String finding,
                          String recommendation) {
    }

    /**
     * A cluster of uncertain questions (NOT_FOUND, UNCITED or grounding-flagged answers).
     *
     * @param askers                  distinct accounts, at least {@code minAskers}
     * @param keywords                words at least {@code minAskers} distinct askers of the cluster used
     * @param representativeQuestions normalised questions at least {@code minAskers} distinct accounts asked; empty unless
     *                                the question-text policy allows it
     */
    public record Cluster(UUID id, int turns, int askers, Integer previousTurns, ClusterTrend trend,
                          List<String> keywords, List<String> representativeQuestions) {

        public Cluster {
            keywords = List.copyOf(keywords);
            representativeQuestions = List.copyOf(representativeQuestions);
        }
    }

    /**
     * A shared document not changed for long yet cited by at least {@code minAskers} distinct accounts in the window.
     *
     * @param fileName   only when the admin may read the document (ADR-0028); null otherwise
     * @param staleCards verified answer cards citing it that went STALE
     */
    public record StaleDocument(UUID documentId, String fileName, UUID collectionId, String collectionName,
                                Instant lastChanged, long citations, int askers, int staleCards) {
    }

    /**
     * Coverage of one shared collection.
     *
     * @param citedInWindow documents cited in the window; null when fewer than {@code minAskers} accounts got a citation
     *                      from this collection (suppressed, k-anonymity)
     * @param coverage      citedInWindow / documents, null under the same condition
     */
    public record CollectionCoverage(UUID collectionId, String collectionName, int documents, Integer citedInWindow,
                                     int neverCited, Double coverage) {
    }

    /**
     * Two shared documents that say nearly the same thing with different numbers or codes.
     *
     * @param values the differing values, only when the admin may read both sides; empty otherwise
     */
    public record Contradiction(UUID id, double similarity, int differences, Side first, Side second,
                                List<Difference> values, Instant firstSeenAt, Instant lastSeenAt) {

        public Contradiction {
            values = List.copyOf(values);
        }
    }

    /**
     * One side of a contradiction: always the document id and the page; the name only when the admin may read it.
     */
    public record Side(UUID documentId, int page, boolean readable, String fileName) {
    }

    /** A value of the first side next to the value of the second side ("14" / "16", "SAT-E058" / "SAT-E059"). */
    public record Difference(String first, String second) {
    }

    /** Verified answer cards by state, and the verified ones whose verification runs out soon. */
    public record Cards(long verified, long draft, long stale, long disputed, long archived, long expiringSoon) {
    }

    /**
     * Down ratings of answers citing one collection, by reason (null: no reason given). Only cells with at least
     * {@code minAskers} distinct raters.
     */
    public record FeedbackCell(UUID collectionId, String collectionName, FeedbackReason reason, long ratings,
                               int askers) {
    }

    /** One run of the trend series. */
    public record RunPoint(UUID runId, LocalDate windowTo, int questions, int clusters) {
    }
}
