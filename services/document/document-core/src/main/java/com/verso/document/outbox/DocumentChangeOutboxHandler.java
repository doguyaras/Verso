package com.verso.document.outbox;

import com.verso.document.api.DocumentChangeListener;
import com.verso.document.api.dto.DocumentChange;
import com.verso.platform.core.outbox.OutboxRelay.OutboxHandler;
import com.verso.platform.core.outbox.OutboxStore.OutboxRow;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Delivers a {@value DocumentChangeOutbox#EVENT_TYPE} row to every {@link DocumentChangeListener} (ADR-0049). The
 * listeners are looked up per delivery (no construction cycle with the modules that implement them) and must be
 * idempotent: a row can be delivered twice, and a failing listener makes the relay deliver it to all of them again.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentChangeOutboxHandler implements OutboxHandler {

    private final ObjectProvider<DocumentChangeListener> listeners;
    private final JsonMapper json;

    public DocumentChangeOutboxHandler(ObjectProvider<DocumentChangeListener> listeners, JsonMapper json) {
        this.listeners = listeners;
        this.json = json;
    }

    @Override
    public void handle(OutboxRow row) {
        DocumentChange change = json.readValue(row.payload(), DocumentChange.class);
        listeners.orderedStream().forEach(listener -> listener.documentChanged(change));
    }
}
