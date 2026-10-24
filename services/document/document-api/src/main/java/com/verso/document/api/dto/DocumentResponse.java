package com.verso.document.api.dto;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.api.enums.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One document the caller may see: a personal document of the caller, or a document of a collection the caller is
 * granted or, for verso-admin, manages (ADR-0028). {@code fileName} is returned only to them, with
 * {@code Cache-Control: private, no-store}; it is never logged (llm-rules 2.1). Counts are null until known;
 * {@code pageCount} counts pages of a PDF and sections of the other formats ({@link DocumentFormat#unit()}).
 * {@code collectionId} and {@code collectionName} are null for a personal document; added last, so the change is
 * additive for existing clients.
 */
public record DocumentResponse(
        UUID id,
        String fileName,
        DocumentFormat format,
        DocumentStatus status,
        long sizeBytes,
        Integer pageCount,
        Integer chunkCount,
        DocumentFailureReason failureReason,
        Instant createdAt,
        Instant updatedAt,
        UUID collectionId,
        String collectionName) {

    /** A personal document. */
    public DocumentResponse(UUID id, String fileName, DocumentFormat format, DocumentStatus status, long sizeBytes,
                            Integer pageCount, Integer chunkCount, DocumentFailureReason failureReason,
                            Instant createdAt, Instant updatedAt) {
        this(id, fileName, format, status, sizeBytes, pageCount, chunkCount, failureReason, createdAt, updatedAt, null,
                null);
    }
}
