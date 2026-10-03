package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.testing.TestPdfs;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** ADR-0016: TXT and MD sections, their encodings and the limits shared with PDF pages. */
class PlainTextExtractorTest {

    private final PlainTextExtractor extractor = new PlainTextExtractor(TestPdfs.properties());

    @Test
    void decode_whenTheEncodingIsMarkedOrUtf8_readsTurkishLetters() {
        String text = "Çalışan İzin Şubat ğüö";
        assertThat(PlainTextExtractor.decode(text.getBytes(StandardCharsets.UTF_8))).isEqualTo(text);
        assertThat(PlainTextExtractor.decode(concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                text.getBytes(StandardCharsets.UTF_8)))).isEqualTo(text);
        assertThat(PlainTextExtractor.decode(concat(new byte[] {(byte) 0xFF, (byte) 0xFE},
                text.getBytes(StandardCharsets.UTF_16LE)))).isEqualTo(text);
        assertThat(PlainTextExtractor.decode(concat(new byte[] {(byte) 0xFE, (byte) 0xFF},
                text.getBytes(StandardCharsets.UTF_16BE)))).isEqualTo(text);
    }

    /** Older Windows programs save Turkish text as Windows-1254: invalid UTF-8, read with that code page instead. */
    @Test
    void decode_whenTheBytesAreNotUtf8_readsThemAsWindows1254() {
        String text = "Çalışan İzin Şubat ğüö";
        assertThat(PlainTextExtractor.decode(text.getBytes(PlainTextExtractor.TURKISH_WINDOWS))).isEqualTo(text);
    }

    @Test
    void decode_whenAByteOrderMarkLies_failsAsInvalid() {
        byte[] lying = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, new byte[] {(byte) 0xC3, (byte) 0x28});
        assertReason(() -> PlainTextExtractor.decode(lying), DocumentFailureReason.INVALID_FILE);
        // Review T6: a UTF-16 mark followed by a lone surrogate is not text either.
        byte[] utf16 = {(byte) 0xFF, (byte) 0xFE, 0x00, (byte) 0xD8, 0x41, 0x00};
        assertReason(() -> PlainTextExtractor.decode(utf16), DocumentFailureReason.INVALID_FILE);
    }

    /** Review B3: the upload looks at the first 8 KB only; NUL bytes later in the file are refused by the worker. */
    @Test
    void extract_whenBinaryDataFollowsTheFirstKilobytes_failsAsInvalid() {
        byte[] file = concat("metin ".repeat(2000).getBytes(StandardCharsets.UTF_8), new byte[] {0, 1, 2, 0});
        assertReason(() -> extractor.extract(file, false), DocumentFailureReason.INVALID_FILE);
    }

    /** Review K5: a hard cut never leaves half of a surrogate pair at the end of a section. */
    @Test
    void lastWhitespace_whenTheLimitFallsInsideASurrogatePair_movesBeforeIt() {
        String text = "a".repeat(9) + "😀" + "b".repeat(10);
        assertThat(TextSections.lastWhitespace(text, 10)).isEqualTo(9);
        assertThat(TextSections.lastWhitespace(text, 11)).isEqualTo(11);
    }

    @Test
    void extract_whenMarkdownHasHeadings_startsASectionAtEachOutsideCodeFences() {
        String md = "# Yıllık izin\nOn dört gün.\n\n## Ücretsiz izin\nOtuz gün.\n\n```\n# bu bir yorum\n```\n";

        List<String> sections = extractor.extract(md.getBytes(StandardCharsets.UTF_8), true);

        assertThat(sections).containsExactly("Yıllık izin\n\nOn dört gün.", "Ücretsiz izin\n\nOtuz gün.\n\n```\n# bu bir yorum\n```");
    }

    @Test
    void extract_whenPlainTextHasAHashLine_doesNotTreatItAsAHeading() {
        assertThat(extractor.extract("# not a heading\nmetin".getBytes(StandardCharsets.UTF_8), false))
                .containsExactly("# not a heading\nmetin");
    }

    /** A section stays about a page long; a single paragraph longer than the page limit is cut at whitespace. */
    @Test
    void extract_whenTextIsLong_cutsSectionsAtParagraphsAndOverlongParagraphsAtWhitespace() {
        String paragraph = "kelime ".repeat(100).strip();
        String text = String.join("\n\n", java.util.Collections.nCopies(20, paragraph));
        List<String> sections = extractor.extract(text.getBytes(StandardCharsets.UTF_8), false);
        assertThat(sections).hasSizeGreaterThan(1).allSatisfy(s -> assertThat(s.length()).isLessThanOrEqualTo(TextSections.TARGET_CHARS));
        assertThat(String.join("", sections).replaceAll("\\s", "")).isEqualTo(text.replaceAll("\\s", ""));

        PlainTextExtractor smallPages = new PlainTextExtractor(TestPdfs.properties(500, 2_000_000, 1000, DataSize.ofMegabytes(64), 500, 50));
        List<String> cut = smallPages.extract("kelime ".repeat(500).getBytes(StandardCharsets.UTF_8), false);
        assertThat(cut).hasSizeGreaterThan(3).allSatisfy(s -> {
            assertThat(s.length()).isLessThanOrEqualTo(1000);
            assertThat(s).as("cut at whitespace, not inside a word").endsWith("kelime");
        });
    }

    @Test
    void extract_whenALimitIsExceeded_failsWithThePdfReasons() {
        PlainTextExtractor twoSections = new PlainTextExtractor(TestPdfs.properties(2, 2_000_000, 1000, 150));
        assertReason(() -> twoSections.extract("# a\nx\n# b\ny\n# c\nz".getBytes(StandardCharsets.UTF_8), true),
                DocumentFailureReason.TOO_MANY_PAGES);
        PlainTextExtractor fewChars = new PlainTextExtractor(TestPdfs.properties(500, 10, 1000, 150));
        assertReason(() -> fewChars.extract("on bir harf!".getBytes(StandardCharsets.UTF_8), false),
                DocumentFailureReason.TOO_MUCH_TEXT);
        assertReason(() -> extractor.extract(" \n\n \t\n".getBytes(StandardCharsets.UTF_8), false), DocumentFailureReason.NO_TEXT);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(a);
        out.writeBytes(b);
        return out.toByteArray();
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, DocumentFailureReason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(IngestionRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
