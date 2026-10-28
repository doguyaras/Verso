package com.verso.work.outbox;

import com.verso.audit.api.AuditTrail;
import com.verso.audit.api.dto.AuditRecord;
import com.verso.platform.core.outbox.OutboxRelay.OutboxHandler;
import com.verso.platform.core.outbox.OutboxStore.OutboxRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Delivers an {@code audit.event.recorded} row of the work outbox to the audit log (ADR-0039); idempotent by event id. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkAuditOutboxHandler implements OutboxHandler {

    private final AuditTrail trail;
    private final JsonMapper json;

    public WorkAuditOutboxHandler(AuditTrail trail, JsonMapper json) {
        this.trail = trail;
        this.json = json;
    }

    @Override
    public void handle(OutboxRow row) {
        trail.store(json.readValue(row.payload(), AuditRecord.class));
    }
}
