package com.verso.qa.api.dto;

/**
 * The {@code error} event, the last one of a failed stream (ADR-0035): the code and the fixed message of the error
 * envelope (ADR-0004), never provider text (llm-rules 2.3). {@code retryAfter} (seconds) is set where the JSON endpoint
 * would send {@code Retry-After}. Nothing was stored.
 */
public record StreamErrorEvent(int code, String message, Integer retryAfter) {
}
