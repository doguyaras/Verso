package com.verso.qa.api.dto;

import java.util.UUID;

/**
 * What one run of the optional suggestion pass did on a document (ADR-0054).
 *
 * @param outcome  STORED, NONE (the model proposed nothing valid), TOO_FEW_ENTITIES, BUSY, TIMEOUT, UNPARSEABLE or
 *                 FAILED; nothing is stored unless STORED
 * @param proposed lines the model returned
 * @param stored   new DRAFT suggestions (a repeated one is stored once)
 * @param rejected proposed lines that failed the checks (unknown entity, wrong types, not in the cited chunk)
 */
public record SystemSuggestionRunResponse(UUID documentId, String outcome, int proposed, int stored, int rejected) {
}
