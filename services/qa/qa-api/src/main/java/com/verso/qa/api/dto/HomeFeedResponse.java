package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * GET /v1/home (ADR-0058): "Ekibinizin son çıktıları" of the home screen for any signed-in caller. Only verified answer
 * cards and approved procedures whose every source the caller may read (ADR-0028), newest first, at most five of
 * each. Never who created or verified them.
 *
 * @param outputs newest first
 */
public record HomeFeedResponse(List<Output> outputs) {

    public HomeFeedResponse {
        outputs = List.copyOf(outputs);
    }

    /**
     * @param kind        CARD (a verified answer, ADR-0049) or PROCEDURE (an approved procedure, ADR-0053)
     * @param title       the card's question or the procedure's name
     * @param description the procedure's description; null for a card
     * @param at          when it was verified or approved
     */
    public record Output(String kind, UUID id, String title, String description, Instant at) {
    }
}
