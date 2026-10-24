package com.verso.connector.api.enums;

/** How a sync run ended (ADR-0038). */
public enum SyncRunStatus {
    /** Still running (or its instance died; the next claim marks it ABANDONED). */
    RUNNING,
    /** The whole source was listed and every change applied. */
    SUCCEEDED,
    /** The whole source was listed, but some files failed, were deferred, or the deletion safety held deletions back. */
    PARTIAL,
    /** The run could not list the source (connection, credentials, egress, collection); nothing was deleted. */
    FAILED,
    /** Its instance stopped mid-run; another run resumed the work idempotently. */
    ABANDONED
}
