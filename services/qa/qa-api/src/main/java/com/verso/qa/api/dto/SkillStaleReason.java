package com.verso.qa.api.dto;

/** Why a skill went {@link SkillState#STALE} (ADR-0053); the first reason is kept. */
public enum SkillStaleReason {
    /** A source document was deleted. */
    SOURCE_DELETED,
    /** A source document was replaced by a new version (connector, ADR-0038). */
    SOURCE_REPLACED,
    /** A source document moved to another collection or space: its readers changed. */
    SOURCE_MOVED,
    /** The text of a bound page no longer has the bound hashes (nightly check). */
    SOURCE_CHANGED
}
