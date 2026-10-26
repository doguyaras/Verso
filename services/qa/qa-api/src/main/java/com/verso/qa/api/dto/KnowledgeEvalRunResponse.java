package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One run of the per-customer golden-set check (ADR-0049): retrieval only, no model call. Every active golden case
 * (a verified answer card) is searched with its stored question vector, as the audience of its cited documents.
 *
 * @param trigger        SCHEDULED (nightly) or MANUAL (an admin asked)
 * @param status         RUNNING, SUCCEEDED or FAILED
 * @param found          cases whose bound chunks were in the top {@code topK}
 * @param missed         cases whose sources exist but were not retrieved
 * @param sourceLost     cases whose bound chunks no longer exist with the same hash (source drift)
 * @param skipped        cases that could not be compared (another embedding model)
 * @param recallAtK      found / (found + missed); null when nothing was comparable
 * @param baselineRecall the recall of the previous successful run, null for the first one
 * @param sourceDrift    sourceLost / cases; null without cases
 * @param alert          the recall fell by more than the configured drop, or the drift passed its limit
 * @param skillCases     golden cases of APPROVED skills checked in the same run (ADR-0053, added last); they do not
 *                       count in the card recall, the baseline or the alert above
 * @param skillFound     skill cases whose expected page was in the top {@code topK}
 * @param skillMissed    skill cases whose expected page exists but was not retrieved
 * @param skillSourceLost skill cases of a skill that lost a bound hash (the skill went STALE)
 */
public record KnowledgeEvalRunResponse(UUID id, String trigger, String status, Instant startedAt, Instant finishedAt,
                                       int cases, int found, int missed, int sourceLost, int skipped, int topK,
                                       Double recallAtK, Double baselineRecall, Double sourceDrift, boolean alert,
                                       int skillCases, int skillFound, int skillMissed, int skillSourceLost) {
}
