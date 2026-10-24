package com.verso.privacy.api.dto;

import java.util.UUID;

/**
 * The redaction's result: how many stored answers that cited the deleted document now hold the fixed notice. A repeat
 * reports 0 (idempotent).
 */
public record AnswerRedactionResponse(UUID documentId, long answersRedacted) {
}
