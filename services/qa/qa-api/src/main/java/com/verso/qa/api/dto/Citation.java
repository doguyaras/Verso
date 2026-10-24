package com.verso.qa.api.dto;

import java.util.UUID;

/**
 * A source the answer refers to with {@code [number]} (llm-rules 3.3): built on the server from the passages that were
 * actually retrieved for this question, never from the model's own text. {@code page} is a page number or a section
 * number, as {@code unit} says (ADR-0016). {@code excerpt} is the part of the cited passage that best supports the
 * answer sentences carrying this marker, or the passage start when none overlaps (ADR-0020): at most 280 code points,
 * whitespace collapsed, {@code …} where cut, always passage text and never model text. It is for display only; it is
 * document text, so it must never be logged or put in metrics (llm-rules 2.1). Added last and optional for clients, so
 * the change is additive. {@code collectionId} and {@code collectionName} name the shared collection holding the
 * document (ADR-0028), null for a personal document; also added last.
 */
public record Citation(int number, UUID documentId, String fileName, int page, CitationUnit unit, String excerpt,
                       UUID collectionId, String collectionName) {

    /** A citation of a personal document. */
    public Citation(int number, UUID documentId, String fileName, int page, CitationUnit unit, String excerpt) {
        this(number, documentId, fileName, page, unit, excerpt, null, null);
    }
}
