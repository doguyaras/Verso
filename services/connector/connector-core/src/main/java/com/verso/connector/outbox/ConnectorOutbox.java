package com.verso.connector.outbox;

import com.verso.platform.core.outbox.OutboxRelay;
import com.verso.platform.core.outbox.OutboxStore;

/**
 * The connector module's outbox {@code connector.outbox_event} (ADR-0039): its store on the svc_connector pool and its
 * relay. A module type on purpose, like the document and qa ones: no module can be handed another's by type.
 */
public record ConnectorOutbox(OutboxStore store, OutboxRelay relay) {
}
