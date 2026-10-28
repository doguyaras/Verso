package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * GET /v1/admin/activity/summary (ADR-0058): the status strip of the activity centre and the home screen's figures.
 *
 * @param attention       entries that need a person (failed runs, regressions, pending approvals), last 7 days
 * @param reasons         at most three short Turkish reasons, most urgent first
 * @param last24h         entries per category that started in the last 24 hours
 * @param pendingApprovals entries of the APPROVAL category
 * @param checkedAt       when this summary was computed
 * @param partial         true when a source could not answer
 */
public record ActivitySummaryResponse(int attention, List<String> reasons, Counts last24h, int pendingApprovals,
                                      Instant checkedAt, boolean partial) {

    public ActivitySummaryResponse {
        reasons = List.copyOf(reasons);
    }

    public record Counts(int runs, int background, int hooks, int knowledge) {
    }
}
