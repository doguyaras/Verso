package com.verso.connector.api.enums;

/**
 * What an admin should conclude about a connector (ADR-0038), derived from the time of the last successful sync and not
 * from the last run's status alone: the top complaint about other products was a "success" badge over an index that
 * had silently stopped.
 */
public enum ConnectorHealth {
    /** The last successful sync is younger than the stale limit and the last run did not fail. */
    OK,
    /** The last run failed as a whole (connection, credentials, egress, collection); earlier data is still served. */
    FAILING,
    /** No successful sync for longer than the stale limit (three schedule intervals); the alert fires too. */
    STALE,
    /** Created but not synced yet. */
    NEVER_SYNCED,
    /** The schedule is paused. */
    DISABLED,
    /** Deleted: its documents are being removed. */
    DELETING
}
