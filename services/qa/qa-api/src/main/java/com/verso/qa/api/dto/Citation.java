package com.verso.qa.api.dto;

import java.util.UUID;

/**
 * A source the answer refers to with {@code [number]} (llm-rules 3.3): built on the server from the passages that were
 * actually retrieved for this question, never from the model's own text. {@code page} is a page number or a section
 * number, as {@code unit} says (ADR-0016).
 */
public record Citation(int number, UUID documentId, String fileName, int page, CitationUnit unit) {
}
