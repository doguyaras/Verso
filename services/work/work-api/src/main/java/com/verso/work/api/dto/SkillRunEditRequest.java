package com.verso.work.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * The owner's edit of a draft (ADR-0057): the whole output, checked against the same schema as the model's (sections,
 * row limits, lengths, evidence numbers that exist, "değerlendirilemez" instead of evidence). An approved run becomes a
 * draft again.
 */
public record SkillRunEditRequest(@NotNull SkillOutput output) {

    @Override
    public String toString() {
        return "SkillRunEditRequest[]";
    }
}
