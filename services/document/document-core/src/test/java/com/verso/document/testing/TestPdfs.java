package com.verso.document.testing;

import com.verso.document.config.DocumentProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.util.unit.DataSize;

/**
 * Synthetic PDFs for tests, built with PDFBox: no real document and no personal data in the repository (llm-rules 10).
 * Text is ASCII because the standard Helvetica font has no Turkish glyphs; extraction itself is encoding-agnostic.
 */
public final class TestPdfs {

    private static final int LINE = 90;

    private TestPdfs() {}

    /** One page per argument; long texts are wrapped onto several lines of the same page. */
    public static byte[] pages(String... texts) {
        return build(texts, null);
    }

    /** Opens only with the user password "secret": Verso rejects it as ENCRYPTED. */
    public static byte[] encrypted(String text) {
        StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "secret", new AccessPermission());
        policy.setEncryptionKeyLength(128);
        return build(new String[]{text}, policy);
    }

    /** Pages without any text, like a scanned document. */
    public static byte[] blank(int pageCount) {
        return build(new String[pageCount], null);
    }

    /** Starts like a PDF (passes the upload check) but is not one. */
    public static byte[] fakePdf() {
        return "%PDF-1.7\nthis is not a pdf body\n".getBytes(StandardCharsets.US_ASCII);
    }

    /** Defaults of DocumentProperties, as config/verso.yml sets them, with a test embedding model name. */
    public static DocumentProperties properties() {
        return properties(500, 2_000_000, 1000, 150);
    }

    public static DocumentProperties properties(int maxPages, int maxTextChars, int chunkSize, int chunkOverlap) {
        return new DocumentProperties(DataSize.ofMegabytes(20), maxPages, maxTextChars, 200, DataSize.ofMegabytes(256),
                chunkSize, chunkOverlap, "test-embedding", 1024,
                new DocumentProperties.Ingestion(true, 5000, Duration.ofMinutes(10), 5, Duration.ofSeconds(30),
                        Duration.ofMinutes(10), 16, 10));
    }

    private static byte[] build(String[] texts, StandardProtectionPolicy protection) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : texts) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text == null || text.isEmpty()) continue;
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 9);
                    content.setLeading(11);
                    content.newLineAtOffset(40, 750);
                    for (String line : wrap(text)) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            if (protection != null) document.protect(protection);
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            String rest = paragraph;
            while (rest.length() > LINE) {
                int cut = rest.lastIndexOf(' ', LINE);
                if (cut <= 0) cut = LINE;
                lines.add(rest.substring(0, cut));
                rest = rest.substring(cut).stripLeading();
            }
            lines.add(rest);
        }
        return lines;
    }
}
