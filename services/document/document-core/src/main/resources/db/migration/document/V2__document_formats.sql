-- DOCX, TXT and MD uploads next to PDF (ADR-0016). The format decides the parser and what a page number means: a
-- printed page for PDF, a section Verso cut for the others. Existing rows are PDFs; the constant default keeps the
-- ALTER a catalogue change (no table rewrite on PostgreSQL 18).
ALTER TABLE document.document ADD COLUMN format text NOT NULL DEFAULT 'PDF';
ALTER TABLE document.document ADD CONSTRAINT ck_document_format CHECK (format IN ('PDF', 'DOCX', 'TXT', 'MD'));

-- Two failure reasons for the new formats: unreadable bytes and an unsafe or unsupported DOCX structure.
ALTER TABLE document.document DROP CONSTRAINT ck_document_failure_reason;
ALTER TABLE document.document ADD CONSTRAINT ck_document_failure_reason CHECK (failure_reason IN
    ('NOT_A_PDF', 'ENCRYPTED', 'TOO_MANY_PAGES', 'TOO_MUCH_TEXT', 'UNSUPPORTED_PDF', 'INVALID_FILE',
     'UNSUPPORTED_FILE', 'NO_TEXT', 'PROCESSING_FAILED'));
