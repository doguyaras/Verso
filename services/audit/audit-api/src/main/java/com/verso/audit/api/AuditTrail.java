package com.verso.audit.api;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.dto.AuditRecord;

/**
 * In-process contract of the audit log's durable path (ADR-0039). A publishing module captures the record while the
 * request is at hand, writes it to its own outbox in the business transaction, and its relay later stores it here.
 * Implemented by the audit module; no other module touches the {@code audit} schema.
 */
public interface AuditTrail {

    /** Event type of the outbox rows that carry an {@link AuditRecord}. */
    String OUTBOX_EVENT_TYPE = "audit.event.recorded";

    /** Aggregate type of those rows. */
    String OUTBOX_AGGREGATE_TYPE = "audit";

    /**
     * Who, where and how, from the current authenticated request: token subject, Verso roles, trace id, AI mode.
     * Throws {@link IllegalStateException} without an authenticated caller (the event cannot be attributed).
     */
    AuditRecord capture(AuditEvent event);

    /**
     * Stores the record once: a second call with the same event id changes nothing (the relay may deliver twice).
     *
     * @return true when this call wrote the row, false when it was already there
     */
    boolean store(AuditRecord record);
}
