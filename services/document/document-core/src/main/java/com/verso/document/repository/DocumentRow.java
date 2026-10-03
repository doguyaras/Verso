package com.verso.document.repository;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

/** One row of document.document as the API sees it; job columns stay inside the repository. */
public record DocumentRow(
        UUID id,
        String accountId,
        String fileName,
        long sizeBytes,
        DocumentStatus status,
        DocumentFailureReason failureReason,
        Integer pageCount,
        Integer chunkCount,
        Instant createdAt,
        Instant updatedAt) {
}
