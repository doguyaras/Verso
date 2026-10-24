package com.verso.connector.outbox;

import com.verso.audit.api.AuditTrail;
import com.verso.audit.api.dto.AuditRecord;
import com.verso.platform.core.outbox.OutboxRelay.OutboxHandler;
import com.verso.platform.core.outbox.OutboxStore.OutboxRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers an {@code audit.event.recorded} row of the connector outbox to the audit log (ADR-0039). Idempotent by the
 * event id: a second delivery of the same row is a no-op at the receiver.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorAuditOutboxHandler implements OutboxHandler {

    private final AuditTrail trail;
    private final JsonMapper json;

    public ConnectorAuditOutboxHandler(AuditTrail trail, JsonMapper json) {
        this.trail = trail;
        this.json = json;
    }

    @Override
    public void handle(OutboxRow row) {
        trail.store(json.readValue(row.payload(), AuditRecord.class));
    }
}
