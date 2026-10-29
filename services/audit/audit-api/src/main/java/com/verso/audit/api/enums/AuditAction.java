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
    MCP_FETCH,
    /** A verso-admin created a file-share connector (ADR-0038); never its host, path or credential name. */
    CONNECTOR_CREATED,
    /** A verso-admin changed a connector's settings. */
    CONNECTOR_UPDATED,
    /** A verso-admin deleted a connector; its documents are removed by the sync worker afterwards. */
    CONNECTOR_DELETED,
    /** A verso-admin asked for a sync now. */
    CONNECTOR_SYNC_REQUESTED,
    /** A sync run started (recorded by the system actor of the connector worker). */
    CONNECTOR_SYNC_STARTED,
    /** A sync run ended; the detail is its status (SUCCEEDED, PARTIAL, FAILED). */
    CONNECTOR_SYNC_FINISHED,
    /** A file of a connector became a document (new or changed at the source); the target is the document id. */
    CONNECTOR_DOCUMENT_ADDED,
    /** A connector's document was deleted because its file left the source (or the connector was deleted). */
    CONNECTOR_DOCUMENT_DELETED,
    /** A verso-admin created a verified answer card from one of their answers (ADR-0049); never its text. */
    KNOWLEDGE_CARD_CREATED,
    /** A verso-admin verified a card; the detail is VERIFIED or VERIFICATION_ADDED (two-person rule pending). */
    KNOWLEDGE_CARD_VERIFIED,
    /** A card went stale (system actor); the detail is the KnowledgeStaleReason. */
    KNOWLEDGE_CARD_STALE,
    /** A card passed the dispute threshold and is no longer served (system actor). */
    KNOWLEDGE_CARD_DISPUTED,
    /** A verso-admin archived a card. */
    KNOWLEDGE_CARD_ARCHIVED,
    /** The golden-set check ran; the detail is SCHEDULED, MANUAL or HOOK, the target is the run. */
    KNOWLEDGE_EVAL_RUN,
    /** The diagnostics job ran (ADR-0052); the detail is SCHEDULED or MANUAL, the target is the run. */
    KNOWLEDGE_DIAGNOSTICS_RUN,
    /** A verso-admin read or exported the knowledge-health report; the detail is VIEW, MARKDOWN or JSON. */
    KNOWLEDGE_HEALTH_VIEWED,
    /**
     * A hook rule acted (system actor); the target is the subject (document, card, cluster, contradiction, eval run),
     * the detail is the rule key. Never the subject's content.
     */
    KNOWLEDGE_HOOK_FIRED,
    /** A verso-admin switched a hook rule on or off; the detail is the rule key with _ON or _OFF. */
    KNOWLEDGE_HOOK_RULE_CHANGED,
    /** A verso-admin marked a hook notification read; the target is the notification. */
    KNOWLEDGE_NOTIFICATION_READ,
    /** A verso-admin drafted a skill (procedure, ADR-0053); the detail is CLUSTER or MANUAL. Never its text. */
    SKILL_DRAFTED,
    /** A verso-admin made a new version of a skill (it is a draft again until approved). */
    SKILL_EDITED,
    /** A verso-admin approved a skill; the detail is APPROVED or APPROVAL_ADDED (two-person rule pending). */
    SKILL_APPROVED,
    /** A verso-admin archived a skill. */
    SKILL_ARCHIVED,
    /** A skill went stale (system actor); the detail is the SkillStaleReason. */
    SKILL_STALE,
    /** A verso-admin downloaded a skill as a SKILL.md bundle. */
    SKILL_EXPORTED,
    /**
     * An MCP client listed the caller's approved skills (ADR-0053); the detail is RESULTS or NO_RESULTS. Never a name.
     */
    MCP_SKILLS,
    /** An MCP client read one approved skill; the target is the skill, the outcome FOUND or NOT_FOUND. */
    MCP_SKILL,
    /**
     * An MCP client looked up a code, term or system in the system model (ADR-0054); the detail is RESULTS or
     * NO_RESULTS, the citation list the documents of the returned sources. Never the query.
     */
    MCP_LOOKUP,
    /** A verso-admin ran the optional local-model suggestion pass on one document (ADR-0054); the detail is the count. */
    SYSTEM_SUGGESTIONS_RUN,
    /** A verso-admin accepted a suggested relation of the system model (ADR-0054). */
    SYSTEM_SUGGESTION_ACCEPTED,
    /** A verso-admin rejected a suggested relation of the system model (ADR-0054). */
    SYSTEM_SUGGESTION_REJECTED,
    /** A user started a task skill run (ADR-0057); the target is the run, the detail BUILT_IN or CUSTOM. Never an input. */
    TASK_SKILL_RUN_STARTED,
    /**
     * A run ended (system actor of the runner); the detail is DRAFT or the failure word, the citation list the
     * documents of its sources. Never the output.
     */
    TASK_SKILL_RUN_FINISHED,
    /** The owner edited a run's output. */
    TASK_SKILL_RUN_EDITED,
    /** The owner marked a run "onaylandı". */
    TASK_SKILL_RUN_APPROVED,
    /** The owner rated a run; the detail is RATING_1 .. RATING_5. */
    TASK_SKILL_RUN_RATED,
    /** The owner exported a run; the detail is MARKDOWN or JSON. */
    TASK_SKILL_RUN_EXPORTED,
    /** The owner deleted a run. */
    TASK_SKILL_RUN_DELETED,
    /** A verso-admin switched a task skill on or off; the detail is ENABLED or DISABLED. */
    TASK_SKILL_TOGGLED,
    /** A verso-admin saved a customer-authored skill as a new draft version; the detail is CREATED or VERSION. */
    TASK_SKILL_CUSTOM_SAVED,
    /** A verso-admin approved a customer-authored skill's current version. */
    TASK_SKILL_CUSTOM_APPROVED,
    /** A verso-admin archived a customer-authored skill. */
    TASK_SKILL_CUSTOM_ARCHIVED,
    /** A verso-admin added a model provider connection (ADR-0056); the detail is the provider type. Never the key. */
    MODEL_CONNECTION_CREATED,
    /** A verso-admin changed a connection's settings (not its key); the detail is ENABLED, DISABLED or SETTINGS. */
    MODEL_CONNECTION_UPDATED,
    /** A verso-admin replaced a connection's API key (rotation); the detail is the new key version. Never the key. */
    MODEL_CONNECTION_KEY_ROTATED,
    /** A connection was tested (on save, rotation or on request); the detail is the result (OK, AUTH_FAILED, ...). */
    MODEL_CONNECTION_TESTED,
    /** A verso-admin deleted a connection and its stored key. */
    MODEL_CONNECTION_DELETED,
    /**
     * A verso-admin changed the gateway: the detail is ROUTE_&lt;TASK&gt;, SETTINGS, PRICE, BUDGET, or PII_MASKING_ON /
     * PII_MASKING_OFF (ADR-0059).
     */
    MODEL_GATEWAY_CHANGED,
    /** A monthly budget reached a limit (system actor); the detail is SOFT or HARD, the target the scope. */
    MODEL_BUDGET_LIMIT_REACHED,
    /** A verso-admin read the gateway's usage and cost report. */
    MODEL_USAGE_VIEWED
}
