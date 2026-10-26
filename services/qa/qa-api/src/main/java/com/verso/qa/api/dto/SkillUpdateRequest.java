package com.verso.qa.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * PUT /v1/admin/skills/{id} (ADR-0053): a new version of a DRAFT or APPROVED skill (an approved one goes back to DRAFT
 * until it is approved again). Steps can be reworded, reordered and removed, never given new sources: each names the
 * step of the current version it comes from and keeps that step's bound chunks. Triggers and golden cases are replaced.
 *
 * @param steps       the new order: {@code from} is a position of the current version, each at most once
 * @param triggers    example questions that should bring this procedure up (1-10)
 * @param goldenCases question, the source page that must come back for it, and an optional fact of that page (0-5;
 *                    approval needs 2-5)
 */
public record SkillUpdateRequest(@NotBlank @Size(max = 64) @Pattern(regexp = SkillDraftRequest.NAME) String name,
                                 @NotBlank @Size(max = 1024) @Pattern(regexp = ONE_LINE) String description,
                                 @NotNull @Size(min = 1, max = 12) List<@Valid @NotNull Step> steps,
                                 @NotNull @Size(min = 1, max = 10)
                                 List<@NotBlank @Size(max = 1000) @Pattern(regexp = ONE_LINE) String> triggers,
                                 @NotNull @Size(max = 5) List<@Valid @NotNull GoldenCase> goldenCases) {

    /** One line, no control or format characters (bidi overrides): shown to users and written into SKILL.md. */
    public static final String ONE_LINE = "[^\\p{Cc}\\p{Cf}]*";

    public record Step(@Min(1) @Max(12) int from,
                       @NotBlank @Size(max = 1000) @Pattern(regexp = ONE_LINE) String instruction) {
    }

    public record GoldenCase(@NotBlank @Size(max = 1000) @Pattern(regexp = ONE_LINE) String question,
                             @NotNull UUID documentId, @Min(1) int page,
                             @Size(min = 1, max = 200) @Pattern(regexp = ONE_LINE) String fact) {
    }

    @Override
    public String toString() {
        return "SkillUpdateRequest[steps=" + (steps == null ? 0 : steps.size()) + ", triggers="
                + (triggers == null ? 0 : triggers.size()) + ", goldenCases="
                + (goldenCases == null ? 0 : goldenCases.size()) + "]";
    }
}
