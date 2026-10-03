package com.verso.qa.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A question about the caller's own documents (llm-rules 3.5: bounded length). Never logged. */
public record QuestionRequest(@NotBlank @Size(max = 1000) String question) {
}
