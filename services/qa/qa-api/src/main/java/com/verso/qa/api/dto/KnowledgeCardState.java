package com.verso.qa.api.dto;

/**
 * The life cycle of a verified answer card (ADR-0049). Only {@link #VERIFIED} cards are served, and only before
 * their expiry. Promotion ({@code VERIFIED}) and {@code ARCHIVED} are human decisions; the system may only demote
 * ({@code STALE}, {@code DISPUTED}).
 */
public enum KnowledgeCardState {
    /** Created from an answer; waits for its verification (or for the second verifier under the two-person rule). */
    DRAFT,
    /** Verified by enough people; served while not expired. */
    VERIFIED,
    /** A cited source changed, was deleted or moved, or the verification expired; not served. */
    STALE,
    /** Enough distinct users rated a served answer down; not served until verified again. */
    DISPUTED,
    /** Withdrawn by an admin; never served again and out of the golden set. */
    ARCHIVED
}
