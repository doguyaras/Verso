package com.verso.connector.outbox;

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
 * What the connector module tells the audit log (ADR-0026, ADR-0038), written to its own outbox in the transaction of
 * the change it reports (ADR-0039): the admin's connector changes as the caller, the sync runs as
 * {@code system:connector-sync}. Ids and fixed words only; never a host, share, path or credential name.
 *
 * <p>Failure split as in the document and qa modules: capturing who did it never fails the change (logged by type,
 * counted as {@code verso.audit.events.not.stored{reason="capture_failed"}}); the outbox insert is part of the
 * transaction and rolls back with it.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorAudit {

    /** The actor of the sync worker's rows. */
    public static final String SYSTEM_COMPONENT = "connector-sync";

    private static final Logger log = LoggerFactory.getLogger(ConnectorAudit.class);

    private final ConnectorOutbox outbox;
    private final AuditTrail trail;
    private final JsonMapper json;
    private final Counter captureFailed;

    public ConnectorAudit(ConnectorOutbox outbox, AuditTrail trail, JsonMapper json, MeterRegistry registry) {
        this.outbox = outbox;
        this.trail = trail;
        this.json = json;
        this.captureFailed = Counter.builder("verso.audit.events.not.stored").tag("reason", "capture_failed")
                .description("Audit events that could not be stored").register(registry);
    }

    /** An admin's change; must run inside the connector transaction of that change. */
    public void append(AuditEvent event) {
        write(event, false);
    }

    /** A sync worker's record; must run inside the connector transaction of that change. */
    public void appendSystem(AuditEvent event) {
        write(event, true);
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
