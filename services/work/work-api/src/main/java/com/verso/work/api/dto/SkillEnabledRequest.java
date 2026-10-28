package com.verso.work.api.dto;

import jakarta.validation.constraints.NotNull;

/** A verso-admin switches a skill on or off for this installation (ADR-0057). */
public record SkillEnabledRequest(@NotNull Boolean enabled) {
}
