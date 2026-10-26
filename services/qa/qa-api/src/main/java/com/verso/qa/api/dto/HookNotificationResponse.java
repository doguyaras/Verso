package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * An in-app notification of a hook (ADR-0052), shared by every admin of the installation: a rule, a subject by id and
 * a fixed word. No account, no question, no document text.
 *
 * @param subjectType DOCUMENT, KNOWLEDGE_CARD, QUESTION_CLUSTER, CONTRADICTION or EVAL_RUN
 * @param detail      a fixed word (a stale reason, a change kind), or null
 * @param title       what happened, in Turkish, from a fixed template
 * @param readAt      when an admin marked it read; null while unread (a FLAG is "waiting for review" until then)
 */
public record HookNotificationResponse(UUID id, String ruleKey, HookAction action, HookTrigger trigger,
                                       String subjectType, UUID subjectId, String detail,
                                       KnowledgeHealthSeverity severity, String title, Instant createdAt,
                                       Instant readAt) {
}
