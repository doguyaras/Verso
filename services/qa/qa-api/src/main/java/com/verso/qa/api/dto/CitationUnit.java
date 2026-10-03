package com.verso.qa.api.dto;

/** What a citation's {@code page} counts: a PDF page, or a section of a DOCX, TXT or MD file (ADR-0016). */
public enum CitationUnit {
    PAGE,
    SECTION
}
