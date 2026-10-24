package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One stored question and its answer (ADR-0023). The citations are references (document, page or section), resolved
 * against the documents when read: a citation of a deleted document keeps its place with
 * {@code documentDeleted = true} and no file name. Passage excerpts are not stored.
 *
 * @param feedback   the caller's own rating of this answer, or null
 * @param durationMs how long the answer took on the server
 */
public record TurnResponse(UUID id, String question, String answer, AnswerOutcome outcome, String mode, String model,
                           List<HistoryCitation> citations, TurnFeedback feedback, Instant createdAt, long durationMs) {

    public TurnResponse {
        citations = List.copyOf(citations);
    }
}
