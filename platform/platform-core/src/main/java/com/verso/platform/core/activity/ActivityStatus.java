package com.verso.platform.core.activity;

/** Where an entry stands; the panel turns each into a word and a tone. */
public enum ActivityStatus {
    RUNNING,
    SUCCEEDED,
    /** Finished with some items failed or held back. */
    PARTIAL,
    FAILED,
    /** A check finished but found a regression (the golden set's recall dropped). */
    REGRESSION,
    /** Waits for a person. */
    PENDING,
    /** A fact, nothing to do. */
    INFO
}
