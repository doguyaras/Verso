package com.verso.qa.api.dto;

/**
 * Counts of a data subject's history (ADR-0034): what exists, or what an erasure removed.
 *
 * @param activeDays rows of qa.active_account_day (the only per-account counter; the daily totals are anonymous)
 */
public record SubjectHistoryCounts(long conversations, long turns, long feedback, long activeDays) {

    public static final SubjectHistoryCounts NONE = new SubjectHistoryCounts(0, 0, 0, 0);

    public SubjectHistoryCounts plus(SubjectHistoryCounts other) {
        return new SubjectHistoryCounts(conversations + other.conversations, turns + other.turns,
                feedback + other.feedback, activeDays + other.activeDays);
    }
}
