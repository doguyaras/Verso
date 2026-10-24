package com.verso.connector.api.enums;

/** State of one tracked file (ADR-0038). */
public enum ItemState {
    /** Its current version is a document of the target collection. */
    SYNCED,
    /** The last attempt failed for a reason that may pass (read error, backlog); retried with backoff. */
    FAILED,
    /** Not ingestible as it is (too large, empty, unsupported, unsafe name or link); tried again only when it changes. */
    SKIPPED
}
