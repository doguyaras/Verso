package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A skill in the admin list (ADR-0053). {@code description} is null when the admin cannot read every source now
 * (ADR-0028: it is derived from document content); {@code readable} says so. The name is an identifier and always shown.
 */
public record SkillSummary(UUID id, String name, String description, SkillState state, SkillStaleReason staleReason,
                           int version, SkillOrigin origin, int steps, int goldenCases, int approvals,
                           int requiredApprovals, Instant approvedAt, Instant updatedAt, boolean readable) {
}
