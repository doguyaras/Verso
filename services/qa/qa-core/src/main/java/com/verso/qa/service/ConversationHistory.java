package com.verso.qa.service;

import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerResponse;
import java.util.Optional;
import java.util.UUID;

/**
 * Where POST /v1/questions keeps its answers (ADR-0023). The history is stored, not used: it does not enter the prompt
 * (follow-up questions are a later step with their own llm review).
 */
public interface ConversationHistory {

    /** Before the model is asked: the conversation must be the caller's and not full (404 / 409, no model call). */
    void checkWritable(AccountId account, UUID conversationId);

    /**
     * Stores the answered question in {@code conversationId}, or in a new conversation when it is null (or was deleted
     * meanwhile), and raises the usage rollups in the same transaction. Empty when the turn could not be stored: the
     * answer is still returned, without ids (the failure is logged without content and counted).
     */
    Optional<StoredTurn> record(AccountId account, UUID conversationId, String question, AnswerResponse answer,
                                Timings timings);

    /** Server-side durations of one answer. */
    record Timings(long retrievalMs, long chatMs, long totalMs) {
    }

    record StoredTurn(UUID conversationId, UUID turnId) {
    }
}
