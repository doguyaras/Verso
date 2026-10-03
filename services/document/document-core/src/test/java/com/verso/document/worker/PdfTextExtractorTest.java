package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.testing.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

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

    /**
     * Review C1: a page whose text positions alone would fill the heap is refused while the parser collects them. The
     * file is a few KB; before the fix a 4 MB page exhausted a 1152 MB heap.
     */
    @Test
    void extract_whenAPageHoldsMoreGlyphsThanAllowed_isRejectedWhileCollecting() {
        byte[] bomb = TestPdfs.textBomb(4_000_000);
        assertThat(bomb.length).as("small on disk").isLessThan(64 * 1024);
        assertRejected(bomb, DocumentFailureReason.TOO_MUCH_TEXT);
        PdfTextExtractor tight = new PdfTextExtractor(TestPdfs.properties(500, 2_000_000, 10, DataSize.ofMegabytes(64), 1000, 150));
        assertThatThrownBy(() -> tight.extract(TestPdfs.pages("eleven char")))
                .isInstanceOfSatisfying(IngestionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentFailureReason.TOO_MUCH_TEXT));
        assertThat(tight.extract(TestPdfs.pages("ten chars!"))).containsExactly("ten chars!");
    }

    /** Review C2: decompressed content is measured with a bounded inflater before the parser decodes it. */
    @Test
    void extract_whenContentInflatesBeyondTheLimit_isRejectedBeforeDecoding() {
        byte[] bomb = TestPdfs.deflateBomb(8 * 1024 * 1024);
        assertThat(bomb.length).isLessThan(64 * 1024);
        PdfTextExtractor tight = new PdfTextExtractor(TestPdfs.properties(500, 2_000_000, 50_000, DataSize.ofMegabytes(1), 1000, 150));
        assertThatThrownBy(() -> tight.extract(bomb)).isInstanceOfSatisfying(IngestionRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(DocumentFailureReason.TOO_MUCH_TEXT));
        // Within the limit it is just an empty page.
        assertRejected(TestPdfs.deflateBomb(64 * 1024), DocumentFailureReason.NO_TEXT);
    }

    /** Security review S1: the graphics state stack is bounded; before, 5M "q" in 10 KB exhausted the heap. */
    @Test
    void extract_whenGraphicsStatesPileUp_isRejectedAsUnsupported() {
        byte[] bomb = TestPdfs.graphicsStateBomb(5_000_000);
        assertThat(bomb.length).isLessThan(64 * 1024);
        assertRejected(bomb, DocumentFailureReason.UNSUPPORTED_PDF);
        assertRejected(TestPdfs.graphicsStateBomb(200), DocumentFailureReason.NO_TEXT);
    }

    @Test
    void extract_whenAContentStreamUsesAnUnmeasurableEncoding_isRejectedAsUnsupported() {
        assertRejected(TestPdfs.lzwContent(), DocumentFailureReason.UNSUPPORTED_PDF);
    }

    /** Test review T16 / ADR-0011: owner-password-only PDFs open without a password and are read. */
    @Test
    void extract_whenOnlyAnOwnerPasswordIsSet_readsTheText() {
        assertThat(extractor.extract(TestPdfs.ownerPasswordOnly("open text"))).containsExactly("open text");
    }

    /** Test review T16: the text limit is inclusive; exactly the limit is accepted. */
    @Test
    void extract_whenTheTextIsExactlyTheLimit_isAccepted() {
        PdfTextExtractor exact = new PdfTextExtractor(TestPdfs.properties(500, 10, 1000, 150));
        assertThat(exact.extract(TestPdfs.pages("0123456789"))).containsExactly("0123456789");
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
