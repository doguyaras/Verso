package com.verso.document.api.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * A change of a document that invalidates what other modules derived from its text or its audience (ADR-0049):
 * delivered through the document outbox (ADR-0039) after the change committed, at least once, to every
 * {@link com.verso.document.api.DocumentChangeListener}. Ids and a fixed word only.
 *
 * @param documentId the document that changed
 * @param kind       what happened
 */
public record DocumentChange(UUID documentId, Kind kind) {

    public DocumentChange {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        /** Deleted by a user, an admin or a connector whose file left the source. */
        DELETED,
        /** Deleted because a connector replaced it with a new version of the file (a new document id). */
        REPLACED,
        /** Moved to another collection or space: its readers changed. */
        MOVED
    }
}
