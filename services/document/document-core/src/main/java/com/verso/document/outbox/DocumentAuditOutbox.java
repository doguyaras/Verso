package com.verso.document.outbox;

import com.verso.audit.api.AuditTrail;
import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.dto.AuditRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the document module tells the audit log (ADR-0026), written to its own outbox in the business transaction
 * (ADR-0039): the row commits or rolls back with the upload, delete, move or collection change, and the relay stores it
 * in the audit log afterwards. Ids and fixed words only; no file name or collection name reaches an {@link AuditEvent}.
 *
 * <p>Failure split: capturing who did it (no authenticated caller, a value the audit log would refuse) never fails the
 * operation; it is logged with the exception type and counted as {@code verso.audit.events.not.stored{reason=
 * "capture_failed"}} (alert AuditEventNotStored). The outbox insert itself is part of the transaction: if it fails, the
 * business change rolls back with it, which is the point of an outbox.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentAuditOutbox {

    private static final Logger log = LoggerFactory.getLogger(DocumentAuditOutbox.class);

    private final DocumentOutbox outbox;
    private final AuditTrail trail;
    private final JsonMapper json;
    private final Counter captureFailed;

    public DocumentAuditOutbox(DocumentOutbox outbox, AuditTrail trail, JsonMapper json,
                               MeterRegistry registry) {
        this.outbox = outbox;
        this.trail = trail;
        this.json = json;
        this.captureFailed = Counter.builder("verso.audit.events.not.stored").tag("reason", "capture_failed")
                .description("Audit events that could not be stored").register(registry);
    }

    /** Must run inside the document transaction of the change it reports (MANDATORY; the store refuses otherwise). */
    public void append(AuditEvent event) {
        write(event, null);
    }

    /**
     * The same for a change without a caller (ADR-0038: a connector's sync through {@code DocumentIngestion}): the actor
     * is {@code system:<component>}.
     */
    public void appendSystem(String component, AuditEvent event) {
        write(event, component);
    }

    private void write(AuditEvent event, String systemComponent) {
        AuditRecord record;
        String payload;
        try {
            record = systemComponent == null ? trail.capture(event) : trail.captureSystem(systemComponent, event);
            payload = json.writeValueAsString(record);
        } catch (RuntimeException e) {
            captureFailed.increment();
            log.error("Audit event not captured: code=AUDIT_CAPTURE_FAILED action={} exceptionType={}", event.action(),
                    e.getClass().getSimpleName());
            return;
        }
        outbox.store().append(record.eventId(), AuditTrail.OUTBOX_AGGREGATE_TYPE,
                event.targetId() != null ? event.targetId() : record.eventId(), AuditTrail.OUTBOX_EVENT_TYPE, payload);
        // Delivered right after the commit when it can be (the poll is the safety net).
        outbox.relay().deliverAfterCommit(record.eventId(), AuditTrail.OUTBOX_EVENT_TYPE, payload);
    }
}
