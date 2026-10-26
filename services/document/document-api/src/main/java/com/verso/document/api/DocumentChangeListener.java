package com.verso.document.api;

import com.verso.document.api.dto.DocumentChange;

/**
 * Implemented by a module that keeps something derived from documents (ADR-0049: the qa module's verified answers).
 * The document module calls every listener from its outbox relay after the change committed (ADR-0039): at least once,
 * possibly twice, in no particular order with other changes. An implementation must be idempotent and must not call
 * back into the document module's write side. An exception leaves the row in the outbox and it is delivered again.
 */
public interface DocumentChangeListener {

    void documentChanged(DocumentChange change);
}
