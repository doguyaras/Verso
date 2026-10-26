package com.verso.audit.api.enums;

/** The kind of thing an audit row points at; the id (when there is one) is a plain UUID, never a name. */
public enum AuditTargetType {
    DOCUMENT,
    /** A shared collection (ADR-0028). */
    COLLECTION,
    /** A file-share connector (ADR-0038). */
    CONNECTOR,
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
    SUBJECT,
    /** A passage (chunk) of a document, read through the MCP server (ADR-0031); the id is the chunk's. */
    PASSAGE,
    /** A verified answer card of the answer memory (ADR-0049). */
    KNOWLEDGE_CARD,
    /** A run of the golden-set check (ADR-0049). */
    KNOWLEDGE_EVAL,
    /** The knowledge-health report or a run of the diagnostics job (ADR-0052). */
    KNOWLEDGE_HEALTH,
    /** A hook rule or a hook notification (ADR-0052). */
    KNOWLEDGE_HOOK,
    /** A cluster of uncertain questions of a diagnostics run (ADR-0052); counts only, no question. */
    QUESTION_CLUSTER,
    /** A contradiction candidate: two chunks of different documents (ADR-0052). */
    CONTRADICTION
}
