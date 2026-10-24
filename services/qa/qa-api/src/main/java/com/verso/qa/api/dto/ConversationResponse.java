package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One conversation with its turns, oldest first (ADR-0023). Only the owner gets it; another account's id is 404. */
public record ConversationResponse(UUID id, String title, Instant createdAt, Instant updatedAt, List<TurnResponse> turns) {

    public ConversationResponse {
        turns = List.copyOf(turns);
    }
}
