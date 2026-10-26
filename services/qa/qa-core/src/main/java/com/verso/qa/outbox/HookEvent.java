package com.verso.qa.outbox;

import com.verso.qa.api.dto.HookTrigger;
import java.util.Objects;
import java.util.UUID;

/**
 * One event for the hook rule engine (ADR-0052), as it travels through the qa outbox: an id (the dedupe key of what a
 * rule does with it), the trigger, the subject by type and id, and a fixed word. Ids and enum names only.
 *
 * @param subjectType DOCUMENT, KNOWLEDGE_CARD, QUESTION_CLUSTER, CONTRADICTION or EVAL_RUN
 * @param detail      a fixed word (a stale reason, a change kind) or null
 */
public record HookEvent(UUID id, HookTrigger trigger, String subjectType, UUID subjectId, String detail) {

    public HookEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        if (detail != null && !detail.matches("[A-Z0-9_]{1,40}")) throw new IllegalArgumentException("detail");
    }

    public static HookEvent of(HookTrigger trigger, String subjectType, UUID subjectId, String detail) {
        return new HookEvent(UUID.randomUUID(), trigger, subjectType, subjectId, detail);
    }
}
