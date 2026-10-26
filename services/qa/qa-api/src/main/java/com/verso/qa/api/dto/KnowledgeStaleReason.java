package com.verso.qa.api.dto;

/** Why a verified answer card went {@link KnowledgeCardState#STALE} (ADR-0049); the first reason is kept. */
public enum KnowledgeStaleReason {
    /** A cited document was deleted. */
    SOURCE_DELETED,
    /** A cited document was replaced by a new version (connector, ADR-0038). */
    SOURCE_REPLACED,
    /** A cited document moved to another collection or space: its readers changed. */
    SOURCE_MOVED,
    /** The text of a cited page no longer has the bound hashes (found by the nightly check). */
    SOURCE_CHANGED,
    /** The verification is older than the re-verification cadence. */
    EXPIRED,
    /**
     * A bound chunk is part of a contradiction candidate and the admins switched on the demoting hook rule
     * (ADR-0052, CONTRADICTION_DEMOTE_CARD; off by default).
     */
    CONTRADICTION
}
