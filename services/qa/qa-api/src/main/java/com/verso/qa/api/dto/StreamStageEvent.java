package com.verso.qa.api.dto;

/**
 * The {@code stage} event of POST /v1/questions/stream (ADR-0035). {@code retrieved} and {@code relevant} are set for
 * {@link AnswerStage#PASSAGES_FOUND} only, otherwise null. Counts, never passage text.
 *
 * <p>{@code step} and {@code steps} (ADR-0055, added last, so the change is additive) are set for
 * {@link AnswerStage#SUB_QUESTION} only ("Alt soru 1/3 aranıyor"), otherwise null. The sub-question text itself comes
 * only in the {@code done} event.
 */
public record StreamStageEvent(AnswerStage stage, Integer retrieved, Integer relevant, Integer step, Integer steps) {

    /** A stage without a sub-question position. */
    public StreamStageEvent(AnswerStage stage, Integer retrieved, Integer relevant) {
        this(stage, retrieved, relevant, null, null);
    }
}
