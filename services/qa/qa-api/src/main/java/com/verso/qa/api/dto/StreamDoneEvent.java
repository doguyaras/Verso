package com.verso.qa.api.dto;

import java.util.UUID;

/**
 * The {@code done} event, the last one of a successful stream (ADR-0035): the same outcome, mode, model and history
 * ids as {@link AnswerResponse}. {@code conversationId} and {@code turnId} are null when the turn could not be stored
 * (ADR-0023); the answer stands. {@code contextQuery} (ADR-0046, added last) as in {@link AnswerResponse}.
 */
public record StreamDoneEvent(AnswerOutcome outcome, boolean found, String mode, String model, UUID conversationId,
                              UUID turnId, String contextQuery) {

    public static StreamDoneEvent of(AnswerResponse answer) {
        return new StreamDoneEvent(answer.outcome(), answer.found(), answer.mode(), answer.model(),
                answer.conversationId(), answer.turnId(), answer.contextQuery());
    }
}
