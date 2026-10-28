package com.verso.work.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Starts a run (ADR-0057). The values are checked against the skill's form: a string for TEXT, LONG_TEXT and SELECT,
 * an array of id strings for DOCUMENTS and COLLECTIONS. The inputs are the caller's own data: stored with the run for
 * its retention period, never logged.
 *
 * @param inputs form field key to value
 */
public record SkillRunRequest(@NotNull @Size(max = 20) Map<String, Object> inputs) {

    /** Never the values (llm-rules 2.1). */
    @Override
    public String toString() {
        return "SkillRunRequest[fields=" + (inputs == null ? 0 : inputs.size()) + "]";
    }
}
