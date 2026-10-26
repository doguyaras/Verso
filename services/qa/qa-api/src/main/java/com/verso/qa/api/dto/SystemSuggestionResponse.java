package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A relation of the system model proposed by the optional local-model pass (ADR-0054), for a verso-admin's review.
 * Entities are named by type and folded key (as the system map lists them); {@code passageId} is the chunk it was read
 * from. A suggestion is never used by retrieval, lookup or the map's deterministic relations.
 *
 * @param state DRAFT, ACCEPTED or REJECTED
 */
public record SystemSuggestionResponse(UUID id, UUID documentId, UUID passageId, String relation, String sourceType,
                                       String sourceKey, String targetType, String targetKey, String state,
                                       Instant createdAt, Instant reviewedAt) {
}
