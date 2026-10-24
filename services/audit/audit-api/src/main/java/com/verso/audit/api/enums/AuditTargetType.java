package com.verso.audit.api.enums;

/** The kind of thing an audit row points at; the id (when there is one) is a plain UUID, never a name. */
public enum AuditTargetType {
    DOCUMENT,
    /** A shared collection (ADR-0028). */
    COLLECTION,
    CONVERSATION,
    TURN,
    /** The usage metrics endpoint (no id). */
    METRICS,
    /** The audit log itself (no id). */
    AUDIT_LOG,
    /** An admin endpoint that refused the caller; {@code detailCode} says which one. */
    ADMIN_API,
    /**
     * A data subject of a KVKK request (ADR-0034). The id is a name-based UUID of the token {@code sub}
     * ({@code UUID.nameUUIDFromBytes("verso:subject:" + sub)}), not the sub itself: it proves the request was handled
     * and can be recomputed from the sub, yet the row does not spell the subject out.
     */
    SUBJECT
}
