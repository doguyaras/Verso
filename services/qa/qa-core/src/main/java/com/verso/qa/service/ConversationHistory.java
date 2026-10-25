package com.verso.qa.service;

import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where POST /v1/questions keeps its answers (ADR-0023). Since ADR-0046 the latest turns of the same conversation are
 * also read back, for one purpose only: rewriting a follow-up question into a standalone search query. They never enter
 * the answer prompt.
 */
public interface ConversationHistory {

    /** Before the model is asked: the conversation must be the caller's and not full (404 / 409, no model call). */
    void checkWritable(AccountId account, UUID conversationId);

    /**
     * The latest {@code turns} turns of the caller's conversation, oldest first (ADR-0046). Empty for a conversation
     * without turns, of another account or deleted meanwhile. Read outside any transaction the model call could hold.
     */
    List<PriorTurn> recent(AccountId account, UUID conversationId, int turns);

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

    /** An earlier question and its answer; {@code answered}: the answer had citations (ADR-0046 uses only those). */
    record PriorTurn(String question, String answer, boolean answered) {

        @Override
        public String toString() {
            return "PriorTurn[answered=" + answered + "]";
        }
    }
}
