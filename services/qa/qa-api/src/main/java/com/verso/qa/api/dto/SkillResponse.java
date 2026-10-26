package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One skill (ADR-0053) as its admin screen shows it: the current version, its steps with their sources, triggers,
 * golden cases, approvals, state history and the version list. Texts are null (lists empty) when the admin cannot read
 * every source now (ADR-0028); state, counts and the history stay visible.
 *
 * @param approvedByYou true when this admin already approved the current cycle (two-person rule)
 * @param createdByYou  true when this admin drafted the skill
 */
public record SkillResponse(UUID id, String name, String description, SkillState state, SkillStaleReason staleReason,
                            int version, SkillOrigin origin, UUID originClusterId, boolean condensed, List<Step> steps,
                            List<String> triggers, List<GoldenCase> goldenCases, int approvals, int requiredApprovals,
                            boolean approvedByYou, boolean createdByYou, List<Event> events, List<VersionInfo> versions,
                            Instant approvedAt, Instant createdAt, Instant updatedAt, boolean readable) {

    /** A step with its sources; {@code grounded}: every number and code of it stands in its sources (ADR-0047). */
    public record Step(int position, String instruction, boolean grounded, List<Source> sources) {
    }

    /** A bound source page; the file name only when this admin reads it. */
    public record Source(UUID documentId, String fileName, int page, CitationUnit unit, boolean accessible) {
    }

    public record GoldenCase(String question, UUID documentId, int page, String fact) {
    }

    public record Event(int version, SkillState from, SkillState to, String reason, Instant at) {
    }

    public record VersionInfo(int version, String reason, boolean condensed, Instant createdAt) {
    }
}
