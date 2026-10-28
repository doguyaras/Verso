package com.verso.work.api.dto;

import com.verso.work.api.enums.CustomSkillState;
import java.time.Instant;
import java.util.UUID;

/** A customer-authored skill with its current version (ADR-0057). No author sub: the audit log says who. */
public record CustomSkillResponse(UUID id, String skillId, CustomSkillState state, int version,
                                  SkillDefinition definition, Instant createdAt, Instant updatedAt,
                                  Instant approvedAt) {
}
