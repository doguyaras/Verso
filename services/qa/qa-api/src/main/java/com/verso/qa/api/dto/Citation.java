package com.verso.qa.api.dto;

import java.util.UUID;

/**
 * A source the answer refers to with {@code [number]} (llm-rules 3.3): built on the server from the passages that were
 * actually retrieved for this question, never from the model's own text. {@code page} is a page number or a section
 * number, as {@code unit} says (ADR-0016). {@code excerpt} is the start of the cited passage (at most 280 characters,
 * whitespace collapsed) for display only; it is document text, so it must never be logged or put in metrics
 * (llm-rules 2.1). Added last and optional for clients, so the change is additive.
 */
public record Citation(int number, UUID documentId, String fileName, int page, CitationUnit unit, String excerpt) {
}
