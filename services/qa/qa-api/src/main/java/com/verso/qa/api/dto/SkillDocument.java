package com.verso.qa.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * One approved skill as SKILL.md text (ADR-0053): frontmatter (name, description) and a body of steps whose references
 * are verso_fetch ids, never document text. {@code documentIds} are the sources (for the audit row).
 */
public record SkillDocument(UUID id, String name, int version, String markdown, List<UUID> documentIds) {

    @Override
    public String toString() {
        return "SkillDocument[id=" + id + ", version=" + version + "]";
    }
}
