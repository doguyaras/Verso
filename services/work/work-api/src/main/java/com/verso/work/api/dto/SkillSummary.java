package com.verso.work.api.dto;

import com.verso.work.api.enums.SkillCategory;
import com.verso.work.api.enums.SkillOrigin;

/**
 * A gallery card (ADR-0057).
 *
 * @param grounded   true when the skill searches the caller's documents
 * @param compliance true for legal and compliance skills (the card shows the review label)
 * @param stepCount  1-4, the pipeline's length (the panel's estimate of the run time)
 */
public record SkillSummary(String id, String version, String name, String description, SkillCategory category,
                           String slash, SkillOrigin origin, boolean grounded, boolean compliance, int stepCount,
                           String disclaimer) {
}
