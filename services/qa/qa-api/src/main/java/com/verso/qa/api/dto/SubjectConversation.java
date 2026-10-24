package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One conversation of a data subject with all its turns, for the KVKK access export (ADR-0034). */
public record SubjectConversation(UUID id, String title, Instant createdAt, Instant updatedAt, List<SubjectTurn> turns) {

    public SubjectConversation {
        turns = List.copyOf(turns);
    }

    /** The title is the start of a question: never in a log line. */
    @Override
    public String toString() {
        return "SubjectConversation[id=" + id + ", turns=" + turns.size() + "]";
    }
}
