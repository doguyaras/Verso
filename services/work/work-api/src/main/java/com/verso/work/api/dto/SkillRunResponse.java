package com.verso.work.api.dto;

import com.verso.work.api.enums.SkillCitationKind;
import com.verso.work.api.enums.SkillRunStage;
import com.verso.work.api.enums.SkillRunStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One run as its owner sees it (ADR-0057). Citations of documents are re-read with the caller's access on every view:
 * a deleted document or one the caller lost access to shows as not accessible, without its text.
 *
 * @param skillVersion the definition version the run used
 * @param stage        the progress stage while RUNNING; FINISHED after
 * @param step         1-based step while GENERATING
 * @param failureCode  a fixed word when FAILED (MODEL_UNAVAILABLE, MODEL_BUSY, OUTPUT_INVALID, RETRIEVAL_FAILED,
 *                     INTERRUPTED, MODEL_TIMEOUT; with the model gateway, ADR-0056: MODEL_BUDGET_EXCEEDED)
 * @param inputs       the caller's form values as submitted
 * @param output       null until finished
 * @param checks       the deterministic checks of the stored output; null until finished
 * @param disclaimer   the skill's label, shown with the output and in every export
 * @param modelId      the chat model that wrote it, as configured
 */
public record SkillRunResponse(UUID id, String skillId, String skillVersion, String skillName, SkillRunStatus status,
                               SkillRunStage stage, Integer step, Integer stepCount, String failureCode,
                               Map<String, Object> inputs, SkillOutput output, List<Citation> citations, Checks checks,
                               boolean edited, Integer rating, String disclaimer, String modelId, Instant createdAt,
                               Instant finishedAt, Instant approvedAt) {

    /**
     * One numbered source.
     *
     * @param number     what {@code evidence} refers to (1-based)
     * @param passageId  PASSAGE: the chunk, readable with document-api's passage lookup under the same access rule
     * @param fileName   PASSAGE: null when no longer accessible
     * @param inputKey   INPUT: the form field the excerpt comes from
     * @param excerpt    the source text (PASSAGE: re-read now; INPUT: the stored part); null when not accessible
     * @param accessible PASSAGE: false when the document was deleted or the caller can no longer read it
     */
    public record Citation(int number, SkillCitationKind kind, UUID passageId, UUID documentId, String fileName,
                           Integer page, String unit, UUID collectionId, String collectionName, String inputKey,
                           String excerpt, boolean accessible) {
    }

    /**
     * What the deterministic checks found (ADR-0057, ADR-0047).
     *
     * @param repaired           the first output did not fit the schema; the one repair attempt did
     * @param rows               rows and text sections in the output
     * @param notAssessable      of them "değerlendirilemez"
     * @param unverified         of them with a number or code their sources do not contain
     * @param unreadableCitation cited sources that could not be read at the end of the run
     * @param withinLength       the output is within the skill's length cap
     * @param disclaimerPresent  the skill's label is attached
     */
    public record Checks(boolean repaired, int rows, int notAssessable, int unverified, int unreadableCitation,
                         boolean withinLength, boolean disclaimerPresent) {
    }
}
