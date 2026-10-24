package com.verso.privacy.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A document the subject uploaded: metadata only, never the file or its text.
 *
 * @param format       PDF, DOCX, TXT or MD
 * @param status       PENDING, PROCESSING, READY or FAILED
 * @param collectionId null for a personal document
 */
public record SubjectDocumentItem(UUID id, String fileName, String format, String status, long sizeBytes,
                                  UUID collectionId, Instant createdAt) {

    @Override
    public String toString() {
        return "SubjectDocumentItem[id=" + id + "]";
    }
}
