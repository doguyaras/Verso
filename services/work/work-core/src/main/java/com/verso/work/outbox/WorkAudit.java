package com.verso.work.outbox;

import com.verso.audit.api.AuditTrail;
import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.dto.AuditRecord;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditOutcome;
import com.verso.audit.api.enums.AuditTargetType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the work module tells the audit log (ADR-0026, ADR-0057), written to its own outbox in the transaction of the
 * change it reports (ADR-0039): the caller's actions as the caller, the end of a run as {@code system:skill-runner}.
 * Ids and fixed words only: never a form value, an output text, a skill name or a file name.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkAudit {

    /** The actor of the runner's rows. */
    public static final String SYSTEM_COMPONENT = "skill-runner";

    private static final Logger log = LoggerFactory.getLogger(WorkAudit.class);

    private final WorkOutbox outbox;
    private final AuditTrail trail;
    private final JsonMapper json;
    private final Counter captureFailed;

    public WorkAudit(WorkOutbox outbox, AuditTrail trail, JsonMapper json, MeterRegistry registry) {
        this.outbox = outbox;
        this.trail = trail;
        this.json = json;
        this.captureFailed = Counter.builder("verso.audit.events.not.stored").tag("reason", "capture_failed")
                .description("Audit events that could not be stored").register(registry);
    }

    /** The audit target id of a skill: name-based for a built-in skill's text id (AuditTargetType.TASK_SKILL). */
    public static UUID skillTarget(String skillId) {
        return UUID.nameUUIDFromBytes(("verso:task-skill:" + skillId).getBytes(StandardCharsets.UTF_8));
    }

    /** A run action of its owner; must run inside the work transaction of that change. */
    public void run(AuditAction action, UUID runId, String detail) {
        write(new AuditEvent(action, AuditOutcome.SUCCESS, AuditTargetType.TASK_SKILL_RUN, runId, null, detail, null,
                null, List.of()), false);
    }

    /** The end of a run (system actor); the citation list holds the documents of its sources. */
    public void finished(UUID runId, String detail, String modelId, List<UUID> documents) {
        // The outcome list is SUCCESS/DENIED: a failed run is a finished operation whose detail is the failure word.
        write(new AuditEvent(AuditAction.TASK_SKILL_RUN_FINISHED, AuditOutcome.SUCCESS, AuditTargetType.TASK_SKILL_RUN, runId, null,
                detail, modelId, null, documents), true);
    }

    /** An admin's change to a skill; must run inside the work transaction of that change. */
    public void skill(AuditAction action, UUID skillTarget, String detail) {
        write(new AuditEvent(action, AuditOutcome.SUCCESS, AuditTargetType.TASK_SKILL, skillTarget, null, detail, null,
                null, List.of()), false);
    }

    private void write(AuditEvent event, boolean system) {
        AuditRecord record;
        String payload;
        try {
            record = system ? trail.captureSystem(SYSTEM_COMPONENT, event) : trail.capture(event);
            payload = json.writeValueAsString(record);
        } catch (RuntimeException e) {
            captureFailed.increment();
            log.error("Audit event not captured: code=AUDIT_CAPTURE_FAILED action={} exceptionType={}", event.action(),
                    e.getClass().getSimpleName());
            return;
        }
        outbox.store().append(record.eventId(), AuditTrail.OUTBOX_AGGREGATE_TYPE,
                event.targetId() != null ? event.targetId() : record.eventId(), AuditTrail.OUTBOX_EVENT_TYPE, payload);
        outbox.relay().deliverAfterCommit(record.eventId(), AuditTrail.OUTBOX_EVENT_TYPE, payload);
    }
}
