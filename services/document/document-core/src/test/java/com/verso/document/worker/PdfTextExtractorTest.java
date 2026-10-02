package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.testing.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Untrusted PDFs end in text per page or in one fixed reason, never in the parser's message (ADR-0011). */
class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor(TestPdfs.properties());

    @Test
    void extract_whenPdfHasText_returnsOneTextPerPageInOrder() {
        List<String> pages = extractor.extract(TestPdfs.pages("First page alpha.", "", "Third page gamma."));
        assertThat(pages).hasSize(3);
        assertThat(pages.get(0)).isEqualTo("First page alpha.");
        assertThat(pages.get(1)).as("a page without text keeps its place").isEmpty();
        assertThat(pages.get(2)).isEqualTo("Third page gamma.");
    }

    @Test
    void extract_whenPasswordProtected_isRejectedAsEncrypted() {
        assertRejected(TestPdfs.encrypted("hidden text"), DocumentFailureReason.ENCRYPTED);
    }

    @Test
    void extract_whenNoPageHasText_isRejectedAsNoText() {
        assertRejected(TestPdfs.blank(3), DocumentFailureReason.NO_TEXT);
    }

    @Test
    void extract_whenBytesOnlyLookLikeAPdf_isRejectedAsNotAPdf() {
        assertRejected(TestPdfs.fakePdf(), DocumentFailureReason.NOT_A_PDF);
        assertRejected("plain text".getBytes(StandardCharsets.US_ASCII), DocumentFailureReason.NOT_A_PDF);
    }

    @Test
    void extract_whenMorePagesThanAllowed_isRejectedBeforeReadingText() {
        PdfTextExtractor strict = new PdfTextExtractor(TestPdfs.properties(2, 2_000_000, 1000, 150));
        assertThatThrownBy(() -> strict.extract(TestPdfs.pages("a", "b", "c")))
                .isInstanceOfSatisfying(IngestionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentFailureReason.TOO_MANY_PAGES));
        assertThat(strict.extract(TestPdfs.pages("a", "b"))).hasSize(2);
    }

    @Test
    void extract_whenTextExceedsTheLimit_isRejectedAsTooMuchText() {
        PdfTextExtractor strict = new PdfTextExtractor(TestPdfs.properties(500, 30, 1000, 150));
        assertThatThrownBy(() -> strict.extract(TestPdfs.pages("twenty characters...", "twenty characters...")))
                .isInstanceOfSatisfying(IngestionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentFailureReason.TOO_MUCH_TEXT));
    }

    /** The rejection carries no cause and no parser text (llm-rules 2.1): only the reason's name. */
    @Test
    void rejection_whenThrown_carriesNoCauseOrParserMessage() {
        assertThatThrownBy(() -> extractor.extract(TestPdfs.fakePdf()))
                .isInstanceOfSatisfying(IngestionRejectedException.class, e -> {
                    assertThat(e.getCause()).isNull();
                    assertThat(e.getMessage()).isEqualTo("NOT_A_PDF");
                    assertThat(e.getStackTrace()).isEmpty();
                });
    }

    @Test
    void normalize_whenTextHasControlCharactersAndRuns_cleansThem() {
        assertThat(PdfTextExtractor.normalize("a\u0000b\r\nc \t  d\n\n\n\ne f "))
                .as("NUL cannot be stored in PostgreSQL text").isEqualTo("a b\nc d\n\ne f");
    }

    private void assertRejected(byte[] pdf, DocumentFailureReason reason) {
        assertThatThrownBy(() -> extractor.extract(pdf))
                .isInstanceOfSatisfying(IngestionRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
