package com.verso.qa.api.dto;

import java.util.List;

/**
 * GET /v1/admin/activity/{id} (ADR-0058): the entry and its drawer. Metadata only: never a prompt, a question, an
 * answer or document text; source names only when the admin may read them.
 */
public record ActivityDetailResponse(ActivityItemResponse item, List<Fact> facts, List<Step> steps,
                                     List<Source> sources, List<ModelCall> modelCalls) {

    public ActivityDetailResponse {
        facts = List.copyOf(facts);
        steps = List.copyOf(steps);
        sources = List.copyOf(sources);
        modelCalls = List.copyOf(modelCalls);
    }

    public record Fact(String label, String value) {
    }

    /** {@code durationMs} null when not measured. */
    public record Step(String label, String status, String detail, Long durationMs) {
    }

    /** {@code name} null when not readable. */
    public record Source(String name, String format, boolean readable, Integer citations) {
    }

    public record ModelCall(String purpose, boolean local, String provider, Long inputTokens, Long outputTokens,
                            Long durationMs) {
    }
}
