package com.verso.qa.api.dto;

import java.util.List;

/**
 * The answer and its sources. {@code found} is false when no passage was close enough (the model is not asked,
 * llm-rules 3.4) or the answer cites nothing; {@code mode} and {@code model} say which model answered (ADR-0006).
 */
public record AnswerResponse(String answer, boolean found, List<Citation> citations, String mode, String model) {
}
