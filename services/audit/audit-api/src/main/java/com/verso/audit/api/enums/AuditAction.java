package com.verso.audit.api.enums;

/**
 * What happened (ADR-0026). A closed list: the audit table stores the name, and a new action is a code change that
 * the reviewer sees, never a free string a caller invents. Names are stable API values (queries filter on them).
 */
public enum AuditAction {
    /** A document was accepted for processing (a replay of the same idempotency key is not a second upload). */
    DOCUMENT_UPLOADED,
    /** A document was deleted with its pages, chunks and vectors. */
    DOCUMENT_DELETED,
    /** A question was answered; only ids, the outcome type and the model are recorded, never the text. */
    QUESTION_ASKED,
    /** One conversation was deleted by its owner. */
    CONVERSATION_DELETED,
    /** Every conversation of the caller was deleted. */
    CONVERSATIONS_DELETED_ALL,
    /** An answer was rated. */
    FEEDBACK_GIVEN,
    /** A verso-admin read the usage metrics. */
    ADMIN_METRICS_VIEWED,
    /** A verso-admin exported the audit log. */
    AUDIT_EXPORTED,
    /** An admin endpoint answered 403 to an authenticated caller who lacks the role. */
    ACCESS_DENIED
}
