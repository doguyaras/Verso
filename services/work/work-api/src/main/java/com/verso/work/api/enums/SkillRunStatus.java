package com.verso.work.api.enums;

/** State of one run (ADR-0057). A finished run is always a DRAFT for a person to check; nothing is final by itself. */
public enum SkillRunStatus {
    RUNNING,
    /** Finished; the output is a draft the owner may edit, approve, export and rate. */
    DRAFT,
    /** The owner marked the draft "onaylandı"; an edit makes it a draft again. */
    APPROVED,
    /** Ended without an output; {@code failureCode} says why in a fixed word. */
    FAILED
}
