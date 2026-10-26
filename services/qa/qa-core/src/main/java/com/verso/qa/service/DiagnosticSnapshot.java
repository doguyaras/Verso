package com.verso.qa.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The aggregates of one diagnostics run, stored as JSON with the run (qa.diagnostic_run.summary, ADR-0052): ids and
 * counts only, every cell already k-anonymous. Names are resolved when the report is read, with the reader's rights.
 */
public record DiagnosticSnapshot(List<StaleDocument> staleDocuments, List<Coverage> coverage, long neverCited,
                                 Cards cards, List<Feedback> feedback) {

    public DiagnosticSnapshot {
        staleDocuments = staleDocuments == null ? List.of() : List.copyOf(staleDocuments);
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
        feedback = feedback == null ? List.of() : List.copyOf(feedback);
        cards = cards == null ? new Cards(0, 0, 0, 0, 0, 0) : cards;
    }

    /** An old shared document cited by at least k distinct accounts in the window. */
    public record StaleDocument(UUID documentId, UUID collectionId, Instant lastChanged, long citations, int askers,
                                int staleCards) {
    }

    /** One collection: documents, those cited in the window (null when suppressed) and those never cited. */
    public record Coverage(UUID collectionId, int documents, Integer citedInWindow, int neverCited) {
    }

    public record Cards(long verified, long draft, long stale, long disputed, long archived, long expiringSoon) {
    }

    /** Down ratings of answers citing one collection, by reason ("NONE" without one); k-anonymous cells only. */
    public record Feedback(UUID collectionId, String reason, long ratings, int askers) {
    }
}
