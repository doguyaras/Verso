package com.verso.document.api.dto;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One document of the caller. {@code fileName} is the caller's own data and is returned only to the owner, with
 * {@code Cache-Control: private, no-store}; it is never logged (llm-rules 2.1). Counts are null until known.
 */
public record DocumentResponse(
        UUID id,
        String fileName,
        DocumentStatus status,
        long sizeBytes,
        Integer pageCount,
        Integer chunkCount,
        DocumentFailureReason failureReason,
        Instant createdAt,
        Instant updatedAt) {
}
