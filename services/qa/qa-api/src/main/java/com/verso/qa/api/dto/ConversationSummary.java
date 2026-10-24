package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One of the caller's conversations in the list (ADR-0023). {@code title} starts as the beginning of the first question
 * and can be renamed; it is the caller's own text, returned only to the owner with {@code no-store} and never logged.
 *
 * @param updatedAt when the last turn was added (a rename does not change it); the list is newest first by this
 * @param turnCount how many turns the history still holds (expired turns are gone)
 */
public record ConversationSummary(UUID id, String title, Instant createdAt, Instant updatedAt, int turnCount) {
}
