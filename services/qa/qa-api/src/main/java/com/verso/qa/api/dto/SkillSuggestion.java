package com.verso.qa.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * A diagnostics cluster (ADR-0052) that can become a procedure (ADR-0053): recurring questions with verified cards
 * around them. Counts and k-anonymous keywords only, never a question of a user.
 *
 * @param verifiedCards VERIFIED cards whose question is near the cluster (the draft's material)
 * @param procedural    at least one of those cards asks "how" ("nasıl", "adım", "başvuru" …): likely a procedure
 */
public record SkillSuggestion(UUID clusterId, UUID runId, List<String> keywords, int turns, int askers,
                              ClusterTrend trend, int verifiedCards, boolean procedural) {
}
