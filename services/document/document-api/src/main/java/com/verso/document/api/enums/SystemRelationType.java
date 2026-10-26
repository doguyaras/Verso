package com.verso.document.api.enums;

/**
 * The closed relation types of the light system model (ADR-0054). MENTIONED_IN (entity to chunk) is not a relation row:
 * every entity carries its mentions, each bound to a chunk id and the chunk's SHA-256.
 */
public enum SystemRelationType {
    /** STEP to its PROCESS. */
    STEP_OF,
    /** STEP to the next STEP of the same process. */
    NEXT_STEP,
    /** CODE to the SYSTEM it belongs to (its prefix, or the one system named on its line). */
    CODE_OF,
    /** SCREEN to the SYSTEM its menu path starts with or its line names. */
    PART_OF,
    /** PROCESS or STEP to the ROLE responsible for it ("X sorumludur", "X tarafından"). */
    OWNED_BY,
    /** TERM or CODE to its definition text (an expansion or a description), not to another entity. */
    DEFINES
}
