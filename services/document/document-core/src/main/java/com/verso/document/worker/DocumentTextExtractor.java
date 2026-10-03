package com.verso.document.worker;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The text of an uploaded file, one string per page (PDF) or section (DOCX, TXT, MD; ADR-0016). The format was decided
 * at upload and stored with the document; every parser ends in a fixed failure reason, never in its own message.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentTextExtractor {

    private final PdfTextExtractor pdf;
    private final DocxTextExtractor docx;
    private final PlainTextExtractor plain;

    public DocumentTextExtractor(PdfTextExtractor pdf, DocxTextExtractor docx, PlainTextExtractor plain) {
        this.pdf = pdf;
        this.docx = docx;
        this.plain = plain;
    }

    public List<String> extract(DocumentFormat format, byte[] content) {
        return switch (format) {
            case PDF -> pdf.extract(content);
            case DOCX -> docx.extract(content);
            case TXT -> plain.extract(content, false);
            case MD -> plain.extract(content, true);
        };
    }

    /** The reason when the stored file is missing: the bytes were never a readable file of this format. */
    static DocumentFailureReason unreadable(DocumentFormat format) {
        return format == DocumentFormat.PDF ? DocumentFailureReason.NOT_A_PDF : DocumentFailureReason.INVALID_FILE;
    }
}
