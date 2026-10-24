package com.verso.qa.outbox;

import com.verso.platform.core.outbox.OutboxRelay;
import com.verso.platform.core.outbox.OutboxStore;

/**
 * The qa module's outbox {@code qa.outbox_event} (ADR-0039): its store on the svc_qa pool and its relay. A module type
 * on purpose, not two generic beans: the document module has its own, and neither can be handed the other's by type.
 */
public record QaOutbox(OutboxStore store, OutboxRelay relay) {
}
