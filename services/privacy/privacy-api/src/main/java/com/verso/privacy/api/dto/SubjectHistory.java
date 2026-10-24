package com.verso.privacy.api.dto;

/**
 * The subject's question history: what exists (summary) or what was deleted (erasure).
 *
 * @param activeDays per-account activity days, the only counter that names an account
 */
public record SubjectHistory(long conversations, long turns, long feedback, long activeDays) {
}
