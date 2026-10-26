package com.verso.qa.api.dto;

import jakarta.validation.constraints.NotNull;

/** PUT /v1/admin/knowledge-hooks/{key}: switches a hook rule on or off; nothing else of a rule can change. */
public record HookRuleUpdateRequest(@NotNull Boolean enabled) {
}
