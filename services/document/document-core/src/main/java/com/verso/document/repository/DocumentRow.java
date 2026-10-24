package com.verso.document.repository;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.api.enums.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of document.document as the API sees it; job columns stay inside the repository. {@code accountId} is the
 * uploader; {@code collectionId} null means the uploader's personal space (ADR-0028).
 */
public record DocumentRow(
        UUID id,
        String accountId,
        String fileName,
        DocumentFormat format,
        long sizeBytes,
        DocumentStatus status,
        DocumentFailureReason failureReason,
        Integer pageCount,
        Integer chunkCount,
        Instant createdAt,
        Instant updatedAt,
        UUID collectionId,
        String collectionName) {

    public boolean personal() {
        return collectionId == null;
    }
}
