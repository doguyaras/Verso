package com.verso.qa.api.dto;

/**
 * The state of a skill (procedure) of the learning layer (ADR-0048 phase 3, ADR-0053). Only an APPROVED skill is ever
 * served (panel, MCP, SKILL.md export); approving and archiving are human, the system only makes a skill STALE.
 */
public enum SkillState {
    /** Drafted or edited; waiting for its approval. Never served. */
    DRAFT,
    /** Approved by enough distinct admins in its cycle; served to callers who read every source. */
    APPROVED,
    /** A bound source was deleted, replaced, moved or changed; never served, needs a new draft. */
    STALE,
    /** Withdrawn by an admin. */
    ARCHIVED
}
