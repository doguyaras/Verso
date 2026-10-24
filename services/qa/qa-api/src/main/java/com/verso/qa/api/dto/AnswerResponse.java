package com.verso.qa.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * The answer and its sources. {@code found} is true only for {@link AnswerOutcome#ANSWERED}; {@code outcome} tells the
 * two false cases apart (not in the documents, llm-rules 3.4, or an uncited model text); {@code mode} and
 * {@code model} say which model answered (ADR-0006).
 *
 * <p>{@code conversationId} and {@code turnId} (ADR-0023, added last, so the change is additive) name where the answer
 * was stored in the caller's history; the client rates the answer with them. Both are null when the turn could not be
 * stored (the answer is returned anyway).
 */
public record AnswerResponse(String answer, boolean found, AnswerOutcome outcome, List<Citation> citations, String mode,
                             String model, UUID conversationId, UUID turnId) {

    /** The same answer, stored in the history as {@code turnId} of {@code conversationId}. */
    public AnswerResponse stored(UUID conversationId, UUID turnId) {
        return new AnswerResponse(answer, found, outcome, citations, mode, model, conversationId, turnId);
    }
}
