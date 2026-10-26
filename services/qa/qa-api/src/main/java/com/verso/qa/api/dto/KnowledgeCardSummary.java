package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One verified answer card in the admin list (ADR-0049). Counts, never who verified or who disputed.
 *
 * <p>{@code readable} (ADR-0028: an admin manages, but reads content only with a grant): false when the calling admin
 * cannot read every document the card cites now (or one was deleted); then {@code question} is null.
 *
 * @param verifiers         distinct verifications of the current cycle
 * @param requiredVerifiers 1, or 2 under the two-person rule
 * @param disputes          distinct users who rated an answer served from this card down, in the current cycle
 */
public record KnowledgeCardSummary(UUID id, String question, KnowledgeCardState state, KnowledgeStaleReason staleReason,
                                   int verifiers, int requiredVerifiers, int disputes, Instant verifiedAt,
                                   Instant expiresAt, Instant createdAt, Instant updatedAt, boolean readable) {

    @Override
    public String toString() {
        return "KnowledgeCardSummary[id=" + id + ", state=" + state + "]";
    }
}
