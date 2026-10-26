package com.verso.qa.service;

import com.verso.document.api.DocumentChangeListener;
import com.verso.document.api.dto.DocumentChange;
import com.verso.qa.api.dto.HookTrigger;
import com.verso.qa.outbox.HookEvent;
import com.verso.qa.repository.ContradictionRepository;
import com.verso.qa.repository.QaDatabase;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The document outbox's {@code document.changed} as a hook trigger (ADR-0052), next to the answer memory's own
 * staleness (KnowledgeTransitions, which does not depend on any rule: a card citing a changed document always goes
 * STALE). A changed document's contradiction candidates are dropped first (they compared text that is gone; the next
 * scan finds what is still true). The event id is derived from the document and the change, so a redelivery is the
 * same event for every rule (idempotent).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HookDocumentListener implements DocumentChangeListener {

    private final QaDatabase db;
    private final ContradictionRepository contradictions;
    private final HookEngine engine;

    public HookDocumentListener(QaDatabase db, ContradictionRepository contradictions, HookEngine engine) {
        this.db = db;
        this.contradictions = contradictions;
        this.engine = engine;
    }

    @Override
    public void documentChanged(DocumentChange change) {
        db.inTransaction(status -> contradictions.deleteForDocument(change.documentId()));
        UUID eventId = UUID.nameUUIDFromBytes(("verso:document-changed:" + change.documentId() + ":" + change.kind())
                .getBytes(StandardCharsets.UTF_8));
        engine.handle(new HookEvent(eventId, HookTrigger.DOCUMENT_CHANGED, "DOCUMENT", change.documentId(),
                change.kind().name()));
    }
}
