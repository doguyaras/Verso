package com.verso.work.api.enums;

/** Progress stage of a run, streamed to the panel (ADR-0057). */
public enum SkillRunStage {
    /** Accepted, waiting for its model slot. */
    QUEUED,
    /** Searching the caller's documents for the grounding passages. */
    RETRIEVING,
    /** Running step {@code step} of {@code stepCount}. */
    GENERATING,
    /** The output did not fit the schema; the one repair attempt is running. */
    REPAIRING,
    /** Deterministic checks: schema, citations, grounding of numbers and codes, length, disclaimer. */
    CHECKING,
    /** Stored (DRAFT) or failed. */
    FINISHED
}
