package com.verso.qa.api.dto;

import java.util.List;

/**
 * GET /v1/admin/skills/{id}/diff (ADR-0053): what changed between two versions, field by field. Texts are sent only to
 * an admin who reads every source of the skill now; otherwise {@code before} and {@code after} are null.
 *
 * @param changes in order: name, description, steps by position, triggers, golden cases
 */
public record SkillVersionDiff(int from, int to, List<Change> changes) {

    /** {@code kind}: ADDED, REMOVED or CHANGED; {@code field}: name, description, step, trigger or goldenCase. */
    public record Change(String field, int position, String kind, String before, String after) {
    }
}
