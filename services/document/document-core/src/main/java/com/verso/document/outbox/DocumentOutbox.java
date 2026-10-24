package com.verso.document.outbox;

import com.verso.platform.core.outbox.OutboxRelay;
import com.verso.platform.core.outbox.OutboxStore;

/**
 * The document module's outbox {@code document.outbox_event} (ADR-0039): its store and its relay. A module type on
 * purpose, not two generic beans: the qa module has its own, and neither can be handed the other's by type.
 */
public record DocumentOutbox(OutboxStore store, OutboxRelay relay) {
}
