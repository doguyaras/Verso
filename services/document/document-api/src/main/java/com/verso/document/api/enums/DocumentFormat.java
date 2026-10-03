package com.verso.document.api.enums;

/**
 * The kinds of file Verso reads (ADR-0011, ADR-0016). A PDF has real pages; the others have none, so their text is cut
 * into numbered sections and cited as such.
 */
public enum DocumentFormat {
    PDF(SourceUnit.PAGE),
    /** Word (Office Open XML), read without Microsoft libraries: the main document part only. */
    DOCX(SourceUnit.SECTION),
    /** Plain text: UTF-8 or UTF-16 with a byte order mark; other bytes are read as Windows-1254 (Turkish). */
    TXT(SourceUnit.SECTION),
    /** Markdown: plain text whose "#" headings start sections. */
    MD(SourceUnit.SECTION);

    private final SourceUnit unit;

    DocumentFormat(SourceUnit unit) {
        this.unit = unit;
    }

    /** What a page number of this format's text means: a printed page, or a section Verso cut. */
    public SourceUnit unit() {
        return unit;
    }
}
