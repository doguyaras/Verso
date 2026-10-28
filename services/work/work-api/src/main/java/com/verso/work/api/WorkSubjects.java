package com.verso.work.api;

/**
 * In-process contract for the privacy module (ADR-0034, ADR-0057): what the skill library holds about one token
 * {@code sub} (its runs with their form inputs and outputs) and their erasure. Counts only; the inputs are never
 * returned or logged through this contract.
 */
public interface WorkSubjects {

    /** How many runs the subject owns. */
    long countRuns(String sub);

    /** Deletes every run of the subject with its citations; returns how many runs were deleted. */
    long deleteRuns(String sub);
}
