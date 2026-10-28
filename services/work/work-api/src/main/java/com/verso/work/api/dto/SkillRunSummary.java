package com.verso.work.api.dto;

import com.verso.work.api.enums.SkillRunStatus;
import java.time.Instant;
import java.util.UUID;

/** One line of the caller's run history (ADR-0057); no input and no output text. */
public record SkillRunSummary(UUID id, String skillId, String skillName, SkillRunStatus status, String failureCode,
                              boolean edited, Integer rating, Instant createdAt, Instant finishedAt) {
}
