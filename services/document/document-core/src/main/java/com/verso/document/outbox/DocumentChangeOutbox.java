package com.verso.document.outbox;

import com.verso.document.api.dto.DocumentChange;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells the modules that derive knowledge from documents that a document changed (ADR-0049): one
 * {@value #EVENT_TYPE} row in {@code document.outbox_event}, in the transaction of the delete, replacement or move, so
 * the notice commits or rolls back with the change (ADR-0039). The relay hands it to every
 * {@link com.verso.document.api.DocumentChangeListener}. Payload: the document id and a fixed word.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentChangeOutbox {

    public static final String EVENT_TYPE = "document.changed";
    public static final String AGGREGATE_TYPE = "document";

    private final DocumentOutbox outbox;
    private final JsonMapper json;

    public DocumentChangeOutbox(DocumentOutbox outbox, JsonMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    /** Must run inside the document transaction of the change (MANDATORY; the store refuses otherwise). */
    public void append(UUID documentId, DocumentChange.Kind kind) {
        UUID eventId = UUID.randomUUID();
        String payload = json.writeValueAsString(new DocumentChange(documentId, kind));
        outbox.store().append(eventId, AGGREGATE_TYPE, documentId, EVENT_TYPE, payload);
        // Delivered right after the commit when it can be (the poll is the safety net).
        outbox.relay().deliverAfterCommit(eventId, EVENT_TYPE, payload);
    }
}
