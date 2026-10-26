package com.verso.qa.api.dto;

import java.util.UUID;

/**
 * The {@code done} event, the last one of a successful stream (ADR-0035): the same outcome, mode, model and history
 * ids as {@link AnswerResponse}. {@code conversationId} and {@code turnId} are null when the turn could not be stored
 * (ADR-0023); the answer stands. {@code contextQuery} (ADR-0046, added last) as in {@link AnswerResponse}.
 * {@code memory} (ADR-0049, added last) as in {@link AnswerResponse}: set when a verified answer card was served.
 * {@code procedure} (ADR-0053, added last) as in {@link AnswerResponse}: an approved skill that matched the question.
 */
public record StreamDoneEvent(AnswerOutcome outcome, boolean found, String mode, String model, UUID conversationId,
                              UUID turnId, String contextQuery, KnowledgeMemory memory, ProcedureMatch procedure) {

    public static StreamDoneEvent of(AnswerResponse answer) {
        return new StreamDoneEvent(answer.outcome(), answer.found(), answer.mode(), answer.model(),
                answer.conversationId(), answer.turnId(), answer.contextQuery(), answer.memory(), answer.procedure());
    }
}
