package com.verso.qa.api.dto;

/**
 * Why an answer was rated down (ADR-0023). A fixed list and no free text in v1: free text could carry personal data
 * and would need its own retention and review. Clients show an unknown value as {@link #OTHER}.
 */
public enum FeedbackReason {
    /** A cited source does not support the sentence; {@code citationNumber} names which one. */
    WRONG_SOURCE,
    /** The answer misses information or states something wrong. */
    INCORRECT_OR_MISSING,
    /** The source is outdated. */
    OUTDATED,
    OTHER
}
