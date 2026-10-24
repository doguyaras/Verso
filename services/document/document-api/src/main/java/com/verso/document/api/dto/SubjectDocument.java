package com.verso.document.api.dto;

import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.api.enums.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * A document a data subject uploaded (ADR-0034): metadata only, never the file or its text.
 *
 * @param collectionId null for a personal document
 */
public record SubjectDocument(UUID id, String fileName, DocumentFormat format, DocumentStatus status, long sizeBytes,
                              UUID collectionId, Instant createdAt) {

    /** The file name is the subject's own text; it stays out of any log line that prints this record. */
    @Override
    public String toString() {
        return "SubjectDocument[id=" + id + ", collectionId=" + collectionId + "]";
    }
}
