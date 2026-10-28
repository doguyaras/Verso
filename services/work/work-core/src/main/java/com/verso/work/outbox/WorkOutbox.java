package com.verso.work.outbox;

import com.verso.platform.core.outbox.OutboxRelay;
import com.verso.platform.core.outbox.OutboxStore;

/**
 * The work module's outbox {@code work.outbox_event} (ADR-0039): its store on the svc_work pool and its relay. A module
 * type on purpose: no module can be handed another's by type.
 */
public record WorkOutbox(OutboxStore store, OutboxRelay relay) {
}
