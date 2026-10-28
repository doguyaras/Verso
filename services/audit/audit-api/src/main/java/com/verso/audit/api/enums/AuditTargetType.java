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
    CONTRADICTION,
    /** A skill (procedure) of the learning layer (ADR-0053). */
    SKILL,
    /** The system model (ADR-0054): a lookup (no id) or a document whose model was looked at. */
    SYSTEM_MODEL,
    /** A suggested relation of the system model awaiting or after an admin's review (ADR-0054). */
    SYSTEM_SUGGESTION,
    /**
     * A task skill of the skill library (ADR-0057). Built-in skills have text ids, so the row's id is a name-based UUID
     * ({@code UUID.nameUUIDFromBytes("verso:task-skill:" + id)}); a customer-authored skill's id is its own UUID.
     */
    TASK_SKILL,
    /** One run of a task skill (ADR-0057). */
    TASK_SKILL_RUN,
    /** A model provider connection of the gateway (ADR-0056). */
    MODEL_CONNECTION,
    /** The model gateway's routes, settings, prices, budgets or usage report (ADR-0056). */
    MODEL_GATEWAY
}
