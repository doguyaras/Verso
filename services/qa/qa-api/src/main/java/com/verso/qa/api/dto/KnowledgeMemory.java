package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The {@code memory} field of an answer served from the answer memory (ADR-0049): which verified answer card answered,
 * when it was verified and until when the verification holds. No verifier identity: who verified is in the audit log.
 */
public record KnowledgeMemory(UUID cardId, Instant verifiedAt, Instant expiresAt) {
}
