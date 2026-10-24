package com.verso.document.api.dto;

import com.verso.document.api.enums.DocumentFormat;
import java.time.Instant;
import java.util.UUID;

/**
 * A document without its owner and without its content (ADR-0023): what the admin usage metrics show.
 * {@code fileName} is the uploader's own text; it is never logged (llm-rules 2.1).
 */
public record DocumentSummary(UUID id, String fileName, DocumentFormat format, Instant createdAt) {
}
