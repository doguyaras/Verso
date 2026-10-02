package com.verso.document.testing;

import com.verso.document.config.DocumentProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayInputStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
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

    /** Encrypted with an owner password only (permissions): readers open it without asking; Verso reads it too. */
    public static byte[] ownerPasswordOnly(String text) {
        StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", "", new AccessPermission());
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

    /**
     * A tiny file that expands into one page with {@code glyphs} characters (phase 4 review C1): the content stream is
     * Flate-compressed, so a few KB on disk become megabytes of text positions in the parser.
     */
    public static byte[] textBomb(int glyphs) {
        return rawPage("BT /F1 1 Tf 0 0 Td (" + "a".repeat(glyphs) + ") Tj ET", COSName.FLATE_DECODE, true);
    }

    /** A compressed content stream that inflates to {@code bytes} bytes of no-op operators (review C2). */
    public static byte[] deflateBomb(int bytes) {
        return rawPage("q Q ".repeat(bytes / 4), COSName.FLATE_DECODE, false);
    }

    /** Security review S1: millions of saved graphics states from a few KB ("q" without "Q"). */
    public static byte[] graphicsStateBomb(int saves) {
        return rawPage("q ".repeat(saves), COSName.FLATE_DECODE, false);
    }

    /** A content stream in LZW encoding: its decoded size cannot be measured without decoding it (UNSUPPORTED_PDF). */
    public static byte[] lzwContent() {
        return rawPage("BT /F1 12 Tf 40 700 Td (lzw text) Tj ET", COSName.LZW_DECODE, true);
    }

    /** Defaults of DocumentProperties, as config/verso.yml sets them, with a test embedding model name. */
    public static DocumentProperties properties() {
        return properties(500, 2_000_000, 1000, 150);
    }

    public static DocumentProperties properties(int maxPages, int maxTextChars, int chunkSize, int chunkOverlap) {
        return properties(maxPages, maxTextChars, 50_000, DataSize.ofMegabytes(64), chunkSize, chunkOverlap);
    }

    public static DocumentProperties properties(int maxPages, int maxTextChars, int maxPageChars, DataSize maxContent,
                                                int chunkSize, int chunkOverlap) {
        return new DocumentProperties(DataSize.ofMegabytes(20), maxPages, maxTextChars, maxPageChars, maxContent, 200, 4, 20,
                DataSize.ofMegabytes(256),
                chunkSize, chunkOverlap, "test-embedding", 1024,
                new DocumentProperties.Ingestion(true, 5000, Duration.ofMinutes(10), 5, Duration.ofSeconds(30),
                        Duration.ofMinutes(10), 16, 10, Duration.ofSeconds(30), Duration.ofMinutes(5)));
    }

    private static byte[] rawPage(String operators, COSName filter, boolean withFont) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (withFont) {
                PDResources resources = new PDResources();
                resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
                page.setResources(resources);
            }
            byte[] content = operators.getBytes(StandardCharsets.US_ASCII);
            page.setContents(new PDStream(document, new ByteArrayInputStream(content), filter));
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
