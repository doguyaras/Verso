package com.verso.qa.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * POST /v1/admin/skills (ADR-0053): a draft procedure, assembled by templates from verified answers and their cited
 * passages. Either {@code clusterId} (the verified cards around a diagnostics cluster) or {@code cardIds} and/or
 * {@code turns} (manual: verified cards, and the admin's own sourced answers). The draft is never served before it is
 * approved.
 *
 * @param name     an optional skill name (lower case, digits, hyphens; default: from the cluster or the first question)
 * @param condense ask the local model to order and condense the steps; every condensed step is re-checked against its
 *                 sources (ADR-0047) and falls back to the template text when a number or code is not there
 */
public record SkillDraftRequest(UUID clusterId,
                                @Size(max = 12) List<@NotNull UUID> cardIds,
                                @Size(max = 12) List<@Valid @NotNull TurnRef> turns,
                                @Size(min = 1, max = 64) @Pattern(regexp = NAME) String name,
                                boolean condense) {

    /** agentskills.io: lower-case letters, digits and single hyphens. */
    public static final String NAME = "[a-z0-9]+(-[a-z0-9]+)*";

    /** One of the admin's own stored answers. */
    public record TurnRef(@NotNull UUID conversationId, @NotNull UUID turnId) {
    }

    @Override
    public String toString() {
        return "SkillDraftRequest[clusterId=" + clusterId + ", cards=" + (cardIds == null ? 0 : cardIds.size())
                + ", turns=" + (turns == null ? 0 : turns.size()) + ", condense=" + condense + "]";
    }
}
