package com.verso.work.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * A customer-authored skill's definition (ADR-0057), created or replaced as a new DRAFT version by a verso-admin. The
 * same validator as the repository's skills checks it; its id may not be a built-in skill's.
 */
public record CustomSkillRequest(@NotNull SkillDefinition definition) {
}
