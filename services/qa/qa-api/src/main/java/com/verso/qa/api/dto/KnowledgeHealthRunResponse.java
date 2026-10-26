package com.verso.qa.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One run of the diagnostics job (ADR-0052): no model call except the local embedding of uncertain questions that were
 * not embedded before.
 *
 * @param trigger                SCHEDULED (nightly) or MANUAL (an admin asked)
 * @param status                 RUNNING, SUCCEEDED or FAILED
 * @param questions              uncertain questions of the window that were clustered
 * @param embedded               of them, embedded in this run
 * @param clusters               clusters with at least k distinct askers
 * @param contradictionsChecked  chunks the contradiction scan looked at in this run
 */
public record KnowledgeHealthRunResponse(UUID id, String trigger, String status, LocalDate windowFrom,
                                         LocalDate windowTo, int questions, int embedded, int clusters,
                                         int contradictionsChecked, Instant startedAt, Instant finishedAt) {
}
