package com.verso.privacy.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * POST /v1/admin/documents/{documentId}/answer-redaction body. {@code confirm} must repeat the document id of the path:
 * the redaction rewrites other people's history and cannot be undone.
 */
public record AnswerRedactionRequest(@NotNull UUID confirm) {
}
