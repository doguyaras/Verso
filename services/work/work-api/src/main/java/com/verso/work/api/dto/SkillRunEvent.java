package com.verso.work.api.dto;

import com.verso.work.api.enums.SkillRunStage;
import com.verso.work.api.enums.SkillRunStatus;

/**
 * One server-sent event of {@code GET /v1/skill-runs/{id}/events} (ADR-0057): event name {@code stage} while the run
 * moves, {@code done} once, when it is stored or failed. No input or output text.
 *
 * @param stepTitle the running step's title from the definition (GENERATING only)
 * @param status    {@code done} only
 */
public record SkillRunEvent(SkillRunStage stage, Integer step, Integer stepCount, String stepTitle,
                            SkillRunStatus status, String failureCode) {
}
