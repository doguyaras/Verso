package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentFormat;
import org.junit.jupiter.api.Test;

/** ADR-0016: a missing file is "not a PDF" only for PDFs (review T5). */
class DocumentTextExtractorTest {

    @Test
    void unreadable_whenTheFormatHasNoPages_isInvalidFileNotNotAPdf() {
        assertThat(DocumentTextExtractor.unreadable(DocumentFormat.PDF)).isEqualTo(DocumentFailureReason.NOT_A_PDF);
        for (DocumentFormat format : new DocumentFormat[] {DocumentFormat.DOCX, DocumentFormat.TXT, DocumentFormat.MD}) {
            assertThat(DocumentTextExtractor.unreadable(format)).as(format.name()).isEqualTo(DocumentFailureReason.INVALID_FILE);
        }
    }
}
