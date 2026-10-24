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
    /** A verso-admin created a shared collection (ADR-0028). */
    COLLECTION_CREATED,
    /** A verso-admin renamed a collection or changed its description. */
    COLLECTION_UPDATED,
    /** A verso-admin deleted an empty collection with its grants. */
    COLLECTION_DELETED,
    /** A collection was granted to a group or a user; the detail says which kind, never the group or the sub. */
    COLLECTION_GRANT_ADDED,
    /** A grant of a collection was removed; same detail as the grant. */
    COLLECTION_GRANT_REMOVED,
    /** A document moved between a personal space and a collection, or between collections. */
    DOCUMENT_MOVED,
    /** An admin endpoint answered 403 to an authenticated caller who lacks the role. */
    ACCESS_DENIED,
    /** A verso-admin read what Verso holds about a data subject (KVKK md. 11, ADR-0034). */
    SUBJECT_VIEWED,
    /** A verso-admin exported a data subject's data (access request, ADR-0034). */
    SUBJECT_EXPORTED,
    /** A verso-admin deleted a data subject's history and personal documents (erasure request, ADR-0034). */
    SUBJECT_DELETED,
    /** A verso-admin replaced the answers that cited a deleted document with a fixed notice (ADR-0034). */
    ANSWERS_REDACTED,
    /**
     * An MCP client searched the caller's passages (ADR-0031); the detail is the outcome word, the citation list the
     * documents returned. Never the query.
     */
    MCP_SEARCH,
    /** An MCP client read one passage by id (ADR-0031); the target is the passage, the outcome FOUND or NOT_FOUND. */
    MCP_FETCH
}
