package com.verso.qa.api.dto;

import java.util.UUID;

/** One approved skill as the MCP tool verso_skills lists it (ADR-0053): the SKILL.md frontmatter's name and description. */
public record SkillListing(UUID id, String name, String description) {
}
