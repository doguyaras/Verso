package com.verso.audit.api.enums;

/** The kind of thing an audit row points at; the id (when there is one) is a plain UUID, never a name. */
public enum AuditTargetType {
    DOCUMENT,
    CONVERSATION,
    TURN,
    /** The usage metrics endpoint (no id). */
    METRICS,
    /** The audit log itself (no id). */
    AUDIT_LOG,
    /** An admin endpoint that refused the caller; {@code detailCode} says which one. */
    ADMIN_API
}
