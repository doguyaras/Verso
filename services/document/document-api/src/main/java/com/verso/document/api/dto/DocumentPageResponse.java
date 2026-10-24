package com.verso.document.api.dto;

import com.verso.document.api.enums.SourceUnit;
import java.util.UUID;

/**
 * GET /v1/documents/{id}/pages/{number} (ADR-0035): the stored text of one page of a PDF, or one section of a DOCX,
 * TXT or MD document ({@code unit}, ADR-0016), as the citations count them. {@code pageCount} bounds the navigation.
 * {@code text} is document text: returned only to a reader of the document with {@code Cache-Control: private,
 * no-store}, never logged (llm-rules 2.1). At most {@link #MAX_TEXT_CHARS} characters; {@code truncated} says a
 * longer page was cut.
 */
public record DocumentPageResponse(UUID documentId, int number, SourceUnit unit, Integer pageCount, String text,
                                   boolean truncated) {

    /**
     * The cap of one response, in code points: above the parser's own page bound (50 000 glyphs,
     * verso.document.max-page-chars), so only an unusual page (a converted table) is ever cut.
     */
    public static final int MAX_TEXT_CHARS = 60_000;
}
