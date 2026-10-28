package com.verso.privacy.api.dto;

/**
 * The subject's question history: what exists (summary) or what was deleted (erasure).
 *
 * @param activeDays per-account activity days, the only counter that names an account
 * @param skillRuns  skill library runs with their form inputs (ADR-0057); added last, so the change is additive
 */
public record SubjectHistory(long conversations, long turns, long feedback, long activeDays, long skillRuns) {

    /** The question history alone (no skill runs). */
    public SubjectHistory(long conversations, long turns, long feedback, long activeDays) {
        this(conversations, turns, feedback, activeDays, 0);
    }
}
