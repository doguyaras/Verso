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
 *
 * <p>{@code contextQuery} (ADR-0046, added last): when a follow-up question was rewritten with the conversation into a
 * standalone search query, that query, so the asker can see how the question was understood. Null when the question
 * was searched as asked. Only the asker receives it; it is neither stored nor logged.
 *
 * <p>{@code unverified} (ADR-0047, added last): the cited sentences whose numbers or codes no cited passage supports,
 * in answer order; empty when every checked sentence is supported. Null when the check is off
 * ({@code verso.qa.grounding.enabled=false}) or there was no model answer. When every cited sentence is unverified the
 * outcome is {@link AnswerOutcome#UNCITED} and {@code found} is false, with the citations still listed, so the reader
 * can compare. Not stored: an answer opened from the history has no {@code unverified}.
 */
public record AnswerResponse(String answer, boolean found, AnswerOutcome outcome, List<Citation> citations, String mode,
                             String model, UUID conversationId, UUID turnId, String contextQuery,
                             List<UnverifiedSentence> unverified) {

    /** An answer without the groundedness check (not found, or the check is off). */
    public AnswerResponse(String answer, boolean found, AnswerOutcome outcome, List<Citation> citations, String mode,
                          String model, UUID conversationId, UUID turnId, String contextQuery) {
        this(answer, found, outcome, citations, mode, model, conversationId, turnId, contextQuery, null);
    }

    /** The same answer, stored in the history as {@code turnId} of {@code conversationId}. */
    public AnswerResponse stored(UUID conversationId, UUID turnId) {
        return new AnswerResponse(answer, found, outcome, citations, mode, model, conversationId, turnId, contextQuery,
                unverified);
    }
}
