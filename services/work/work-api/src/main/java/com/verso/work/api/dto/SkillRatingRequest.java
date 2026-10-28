package com.verso.work.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** The owner's rating of a finished run, 1-5 (ADR-0057); a second rating replaces the first. No free text. */
public record SkillRatingRequest(@NotNull @Min(1) @Max(5) Integer rating) {
}
