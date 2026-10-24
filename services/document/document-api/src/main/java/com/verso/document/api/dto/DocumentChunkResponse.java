package com.verso.document.api.dto;

import com.verso.document.api.enums.SourceUnit;

/**
 * One item of GET /v1/admin/documents/{id}/chunks (ADR-0036): a retrieval chunk of a document the admin reads, in
 * chunk order. {@code number} is the page of a PDF or the section of the other formats ({@code unit}, ADR-0016), the
 * same number the page viewer opens (ADR-0035). {@code charCount} is the whole chunk's length in code points;
 * {@code text} is its first {@link #MAX_TEXT_CHARS} code points and {@code truncated} says it was cut. Document text:
 * {@code Cache-Control: private, no-store}, never logged (llm-rules 2.1). No embedding or vector leaves the server.
 */
public record DocumentChunkResponse(int index, int number, SourceUnit unit, int charCount, String text,
                                    boolean truncated) {

    /** Enough for a preview and a search within the page of chunks; the full text is the page viewer's. */
    public static final int MAX_TEXT_CHARS = 2_000;
}
