package com.verso.qa.api.dto;

import java.util.List;

/**
 * The {@code citations} event (ADR-0035): the validated answer, exactly as POST /v1/questions returns it in
 * {@code answer} and {@code citations} (llm-rules 3.3: markers mapped on the server to retrieved passages, invalid
 * numbers removed). It replaces the provisional text of the {@code delta} events. An empty list is an answer without a
 * source (the {@code done} event says UNCITED or NOT_FOUND).
 */
public record StreamCitationsEvent(String answer, List<Citation> citations) {
}
