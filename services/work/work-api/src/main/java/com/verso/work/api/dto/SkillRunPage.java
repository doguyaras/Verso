package com.verso.work.api.dto;

import java.util.List;

/** One page of the caller's runs, newest first (ADR-0057). */
public record SkillRunPage(List<SkillRunSummary> items, int page, int size, long total) {
}
