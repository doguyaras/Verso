package com.verso.qa.outbox;

import com.verso.audit.api.AuditTrail;
import com.verso.audit.api.dto.AuditRecord;
import com.verso.platform.core.outbox.OutboxRelay.OutboxHandler;
import com.verso.platform.core.outbox.OutboxStore.OutboxRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers an {@code audit.event.recorded} row of the qa outbox to the audit log (ADR-0039). Idempotent by the event
 * id: when the relay delivers a row twice (crash after the insert, lease ran out) the second store is a no-op. A
 * payload that no longer reads as an {@link AuditRecord} is not transient and ends DEAD after the attempts.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QaAuditOutboxHandler implements OutboxHandler {

    private final AuditTrail trail;
    private final JsonMapper json;

    public QaAuditOutboxHandler(AuditTrail trail, JsonMapper json) {
        this.trail = trail;
        this.json = json;
    }

    @Override
    public void handle(OutboxRow row) {
        trail.store(json.readValue(row.payload(), AuditRecord.class));
    }
}
