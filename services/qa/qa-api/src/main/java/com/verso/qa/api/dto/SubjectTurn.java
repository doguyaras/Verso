package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One stored question and answer of a data subject (ADR-0034), with its citation references and the subject's rating.
 *
 * @param feedback null when the subject did not rate the answer
 */
public record SubjectTurn(UUID id, Instant createdAt, String question, String answer, String outcome, String mode,
                          String model, List<SubjectCitation> citations, TurnFeedback feedback) {

    public SubjectTurn {
        citations = List.copyOf(citations);
    }

    /** Question and answer are content: never in a log line. */
    @Override
    public String toString() {
        return "SubjectTurn[id=" + id + ", outcome=" + outcome + "]";
    }

    /** A citation reference as stored (ADR-0023): document id and page or section, no passage text. */
    public record SubjectCitation(int number, UUID documentId, int page, CitationUnit unit) {
    }
}
