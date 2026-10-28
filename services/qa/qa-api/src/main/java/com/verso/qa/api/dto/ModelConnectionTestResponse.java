package com.verso.qa.api.dto;

/**
 * The result of a connection test (ADR-0056): a tiny completion with the first model of the connection. Never the
 * provider's own message, which may quote the request or the key (llm-rules 2.3).
 *
 * @param status OK, AUTH_FAILED, UNREACHABLE, MODEL_NOT_FOUND, RATE_LIMITED or FAILED
 */
public record ModelConnectionTestResponse(boolean ok, String status, long latencyMs) {
}
