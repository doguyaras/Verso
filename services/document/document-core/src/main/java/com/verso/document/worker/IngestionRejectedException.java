package com.verso.document.worker;

import com.verso.document.api.enums.DocumentFailureReason;

/** A permanent ingestion failure: the same file would fail again, so the document becomes FAILED without retry. */
public class IngestionRejectedException extends RuntimeException {

    private final DocumentFailureReason reason;

    public IngestionRejectedException(DocumentFailureReason reason) {
        // No cause and no parser message: both can carry text of the document (llm-rules 2.1).
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public DocumentFailureReason reason() {
        return reason;
    }
}
