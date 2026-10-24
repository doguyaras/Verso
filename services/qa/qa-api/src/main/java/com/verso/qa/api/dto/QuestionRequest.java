package com.verso.qa.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A question about the caller's own documents (llm-rules 3.5: bounded length). Never logged.
 *
 * @param question       the question text
 * @param conversationId optional (ADR-0023): one of the caller's conversations to add this turn to; absent starts a
 *                       new conversation. Another account's id is "not found". The history does not enter the prompt:
 *                       every question is answered on its own.
 */
public record QuestionRequest(@NotBlank @Size(max = 1000) String question, UUID conversationId) {
}
