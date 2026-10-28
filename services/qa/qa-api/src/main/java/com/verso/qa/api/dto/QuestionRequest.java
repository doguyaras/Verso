package com.verso.qa.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A question about the caller's own documents (llm-rules 3.5: bounded length). Never logged.
 *
 * @param question       the question text
 * @param conversationId optional (ADR-0023): one of the caller's conversations to add this turn to; absent starts a
 *                       new conversation. Another account's id is "not found". The history does not enter the prompt:
 *                       every question is answered on its own.
 * @param model          optional (ADR-0056): a choice id of GET /v1/models; only when a verso-admin allowed the choice,
 *                       otherwise 400 {@code MODEL_CHOICE_INVALID}. Absent: the answer route as configured.
 */
public record QuestionRequest(@NotBlank @Size(max = 1000) String question, UUID conversationId,
                              @Size(max = 260) @Pattern(regexp = "built-in|[0-9a-f-]{36}/[A-Za-z0-9._:/@-]{1,200}")
                              String model) {

    /** The request of clients that do not pick a model (the 1.0 shape). */
    public QuestionRequest(String question, UUID conversationId) {
        this(question, conversationId, null);
    }
}
