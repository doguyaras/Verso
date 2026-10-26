package com.verso.qa.api.dto;

/** How a cluster of uncertain questions changed against the previous diagnostics run (ADR-0052). */
public enum ClusterTrend {
    /** No similar cluster in the previous run. */
    NEW,
    /** At least a quarter more questions than the matching cluster of the previous run. */
    GROWING,
    /** Within a quarter of the previous size. */
    STABLE,
    /** At least a fifth fewer questions. */
    SHRINKING
}
