package com.verso.qa.api.dto;

import java.util.List;

/**
 * The answer and its sources. {@code found} is true only for {@link AnswerOutcome#ANSWERED}; {@code outcome} tells the
 * two false cases apart (not in the documents, llm-rules 3.4, or an uncited model text); {@code mode} and
 * {@code model} say which model answered (ADR-0006).
 */
public record AnswerResponse(String answer, boolean found, AnswerOutcome outcome, List<Citation> citations, String mode,
                             String model) {
}
