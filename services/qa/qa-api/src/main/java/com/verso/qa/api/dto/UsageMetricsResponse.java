package com.verso.qa.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GET /v1/admin/metrics (ADR-0023), {@code verso-admin} only. Aggregates of the stored answers between {@code from}
 * and {@code to} (inclusive days in {@code timeZone}). Never an account id, a user name or an answer; question texts
 * only in {@link UnansweredQuestions}, only when the installation allows it, and only for questions at least
 * {@code minDistinctUsers} different accounts asked (k-anonymity).
 *
 * @param totals                the whole range
 * @param daily                 one entry per day of the range, empty days included (zeros)
 * @param documentNamesShown    the installation's policy (verso.qa.metrics.show-document-names, off by default): when
 *                              false every document {@code fileName} below is null and documents are known by id only
 * @param topCitedDocuments     most cited documents in the range, at most 10
 * @param neverCitedDocuments   READY documents no stored answer has ever cited
 * @param unansweredQuestions   questions answered "not found", grouped, k-anonymous; empty and {@code enabled = false}
 *                              unless the installation turned it on
 */
public record UsageMetricsResponse(LocalDate from, LocalDate to, String timeZone, Totals totals, List<Day> daily,
                                   boolean documentNamesShown, List<CitedDocument> topCitedDocuments, NeverCitedDocuments neverCitedDocuments,
                                   UnansweredQuestions unansweredQuestions) {

    public UsageMetricsResponse {
        daily = List.copyOf(daily);
        topCitedDocuments = List.copyOf(topCitedDocuments);
    }

    /**
     * @param activeUsers         distinct accounts that asked at least once in the range (a count only)
     * @param activeUsersComplete false when part of the range is older than the history retention: the distinct count
     *                            then covers only the retained days (the daily counts stay exact)
     */
    public record Totals(long questions, long activeUsers, boolean activeUsersComplete, Outcomes outcomes,
                         OutcomeRates outcomeRates, Latency latency, Modes modes, Feedback feedback) {
    }

    /** Stored answers by outcome (AnswerOutcome); failed questions (503) are not stored and not counted here. */
    public record Outcomes(long answered, long notFound, long uncited) {
    }

    /** Shares of all questions, 0..1 with four decimals; null when there was no question. */
    public record OutcomeRates(Double answered, Double notFound, Double uncited) {
    }

    /** Server-side answer time; upper bounds of a fixed histogram bucket, null without answers. */
    public record Latency(Long p50Ms, Long p95Ms) {
    }

    public record Modes(long local, long cloud) {
    }

    /**
     * Ratings of the answers asked in the range (counted on the day of the answer, a new rating replaces the old one).
     *
     * @param downReasons down ratings per reason; {@code downWithoutReason} counts those that gave none
     */
    public record Feedback(long up, long down, Map<FeedbackReason, Long> downReasons, long downWithoutReason) {

        public Feedback {
            // Enum order in the JSON, so that the panel does not have to sort.
            java.util.EnumMap<FeedbackReason, Long> ordered = new java.util.EnumMap<>(FeedbackReason.class);
            ordered.putAll(downReasons);
            downReasons = java.util.Collections.unmodifiableMap(ordered);
        }
    }

    public record Day(LocalDate day, long questions, long activeUsers, Outcomes outcomes, Latency latency, Modes modes,
                      long feedbackUp, long feedbackDown) {
    }

    /**
     * @param fileName        null when the document has been deleted since ({@code documentDeleted}) or names are not
     *                        shown ({@code documentNamesShown = false})
     */
    public record CitedDocument(UUID documentId, String fileName, boolean documentDeleted, long citations) {
    }

    /**
     * At most 50 documents, oldest first; {@code total} counts all of them. {@code complete} is false when more
     * documents were ever cited than one lookup takes (10 000): the list may then also hold cited documents.
     */
    public record NeverCitedDocuments(long total, boolean complete, List<DocumentEntry> documents) {

        public NeverCitedDocuments {
            documents = List.copyOf(documents);
        }
    }

    public record DocumentEntry(UUID documentId, String fileName, Instant createdAt) {
    }

    /**
     * @param enabled          the installation's policy (verso.qa.metrics.show-unanswered-questions, off by default)
     * @param minDistinctUsers a question is listed only when at least this many accounts asked it (at least 3)
     * @param questions        normalised text (lower case, single spaces), most askers first, at most 20
     */
    public record UnansweredQuestions(boolean enabled, int minDistinctUsers, List<UnansweredQuestion> questions) {

        public UnansweredQuestions {
            questions = List.copyOf(questions);
        }
    }

    public record UnansweredQuestion(String question, long distinctUsers, long asked) {
    }
}
