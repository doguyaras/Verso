package com.verso.qa.api.dto;

/**
 * The {@code stage} event of POST /v1/questions/stream (ADR-0035). {@code retrieved} and {@code relevant} are set for
 * {@link AnswerStage#PASSAGES_FOUND} only, otherwise null. Counts, never passage text.
 */
public record StreamStageEvent(AnswerStage stage, Integer retrieved, Integer relevant) {
}
