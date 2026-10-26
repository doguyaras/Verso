package com.verso.qa.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One verified answer card with its history (ADR-0049). The citations resolve against the documents with the admin's
 * own access, like a history citation (ADR-0023, ADR-0028). Disputes are counted per day only (no user-level
 * drill-down; works councils, ADR-0048), and the events say what changed and why, not who did it (the audit log does).
 *
 * <p>{@code readable} as in {@link KnowledgeCardSummary}: when false, {@code question} and {@code answer} are null and
 * {@code keyFacts} empty (ADR-0028); the state, the counts and the history stay visible so the card can be archived.
 *
 * @param createdByYou  the calling admin created the card
 * @param verifiedByYou the calling admin verified it in the current cycle (the two-person rule needs someone else)
 */
public record KnowledgeCardResponse(UUID id, String question, String answer, KnowledgeCardState state,
                                    KnowledgeStaleReason staleReason, List<String> keyFacts,
                                    List<HistoryCitation> citations, int verifiers, int requiredVerifiers,
                                    boolean createdByYou, boolean verifiedByYou, int disputes,
                                    List<DisputeDay> disputesByDay, List<Event> events, Instant verifiedAt,
                                    Instant expiresAt, Instant createdAt, Instant updatedAt, boolean readable) {

    public KnowledgeCardResponse {
        keyFacts = List.copyOf(keyFacts);
        citations = List.copyOf(citations);
        disputesByDay = List.copyOf(disputesByDay);
        events = List.copyOf(events);
    }

    /** Disputes recorded on one calendar day (metrics time zone). */
    public record DisputeDay(LocalDate day, int disputes) {
    }

    /**
     * One state change: from, to, and a fixed reason word (CREATED, VERIFIED, VERIFICATION_ADDED, a
     * {@link KnowledgeStaleReason}, DISPUTE_THRESHOLD, ARCHIVED).
     */
    public record Event(KnowledgeCardState fromState, KnowledgeCardState toState, String reason, Instant at) {
    }

    @Override
    public String toString() {
        return "KnowledgeCardResponse[id=" + id + ", state=" + state + "]";
    }
}
