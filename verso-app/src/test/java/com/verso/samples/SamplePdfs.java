package com.verso.samples;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * Builds the synthetic sample PDFs of samples/ from samples/src/*.txt (phase 8 eval set, phase 9 demo). Turkish text
 * with the standard Helvetica font, no embedded font file: Helvetica has the glyphs (gbreve, dotlessi, scedilla, ...)
 * but WinAnsiEncoding cannot address them, so a /Differences array maps six unused codes to them. Text extraction
 * reads the glyph names back as Unicode (Idotaccent for İ is encoded by hand: PDFBox looks it up as "Idot"). The output is byte-for-byte reproducible (fixed document id, no dates).
 *
 * <p>Source format: the first line is the title, "---" starts a new page, an empty line is a paragraph break.
 */
public final class SamplePdfs {

    private static final float MARGIN = 56;
    private static final float SIZE = 11;
    private static final float TITLE_SIZE = 13;
    private static final float LEADING = 15;

    private SamplePdfs() {
    }

    /**
     * How "a | b" rows are drawn. TEXT: as lines of text with the pipes (the committed samples). GRID: as a ruled table
     * without pipes, the header row bold, like a table exported from a word processor (ADR-0032 measurement; generated
     * on demand, not committed).
     */
    public enum Tables { TEXT, GRID }

    public static byte[] build(String name, String source) {
        return build(name, source, Tables.TEXT);
    }

    public static byte[] build(String name, String source, Tables tables) {
        try (PDDocument document = new PDDocument()) {
            PDType1Font regular = turkish("Helvetica");
            PDType1Font bold = turkish("Helvetica-Bold");
            List<List<String>> pages = pages(source);
            String title = pages.getFirst().getFirst();
            pages.getFirst().removeFirst();
            for (int i = 0; i < pages.size(); i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                float width = page.getMediaBox().getWidth() - 2 * MARGIN;
                float y = page.getMediaBox().getHeight() - MARGIN;
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    if (i == 0) y = line(content, bold, TITLE_SIZE, title, y) - LEADING / 2;
                    List<String> paragraphs = pages.get(i);
                    for (int p = 0; p < paragraphs.size(); p++) {
                        String paragraph = paragraphs.get(p);
                        if (paragraph.isBlank()) {
                            y -= LEADING / 2;
                            continue;
                        }
                        if (tables == Tables.GRID && paragraph.contains(" | ")) {
                            int end = p;
                            while (end < paragraphs.size() && paragraphs.get(end).contains(" | ")) end++;
                            y = grid(content, regular, bold, paragraphs.subList(p, end), y, width);
                            if (y < MARGIN / 2 + 20) throw new IllegalStateException(name + ": page " + (i + 1) + " overflows");
                            p = end - 1;
                            continue;
                        }
                        boolean heading = paragraph.matches("\\d+\\. .{0,60}");
                        PDType1Font font = heading ? bold : regular;
                        for (String text : wrap(paragraph, font, SIZE, width)) y = line(content, font, SIZE, text, y);
                    }
                    line(content, regular, 8, name + " — sayfa " + (i + 1) + " / " + pages.size(), MARGIN / 2 + 8);
                }
            }
            byte[] id = md5(name);
            COSArray ids = new COSArray();
            ids.add(new COSString(id));
            ids.add(new COSString(id));
            document.getDocument().getTrailer().setItem(COSName.ID, ids);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A standard 14 font with WinAnsiEncoding plus the six Turkish letters it lacks. */
    private static PDType1Font turkish(String baseFont) {
        COSArray differences = new COSArray();
        String[][] codes = {{"129", "gbreve"}, {"141", "Gbreve"}, {"143", "dotlessi"}, {"144", "Idotaccent"},
                {"157", "scedilla"}, {"164", "Scedilla"}};
        for (String[] code : codes) {
            differences.add(COSInteger.get(Integer.parseInt(code[0])));
            differences.add(COSName.getPDFName(code[1]));
        }
        COSDictionary encoding = new COSDictionary();
        encoding.setItem(COSName.TYPE, COSName.ENCODING);
        encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
        encoding.setItem(COSName.DIFFERENCES, differences);
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE1);
        font.setItem(COSName.BASE_FONT, COSName.getPDFName(baseFont));
        font.setItem(COSName.ENCODING, encoding);
        try {
            return new PDType1Font(font) {
                // PDFBox's glyph list names U+0130 "Idot"; Helvetica calls it "Idotaccent" (code 144 above).
                @Override
                protected byte[] encode(int unicode) throws IOException {
                    return unicode == 0x130 ? new byte[]{(byte) 144} : super.encode(unicode);
                }
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Rows "a | b | c" as a ruled table: column widths follow the longest cell, shrunk to the page width; cells wrap;
     * the first row is the bold header. Returns the baseline of the next line below the table.
     */
    private static float grid(PDPageContentStream content, PDType1Font regular, PDType1Font bold, List<String> rows,
                              float y, float width) throws IOException {
        float pad = 3;
        List<String[]> cells = rows.stream().map(r -> r.split(" \\| ")).toList();
        int columns = cells.stream().mapToInt(c -> c.length).max().orElse(1);
        // A column is at least as wide as its longest word, so no word runs into the next cell; the rest of the page
        // width goes to the columns in proportion to how much longer their text is.
        float[] natural = new float[columns];
        float[] minimum = new float[columns];
        for (int r = 0; r < cells.size(); r++) {
            PDType1Font font = r == 0 ? bold : regular;
            for (int c = 0; c < cells.get(r).length; c++) {
                natural[c] = Math.max(natural[c], font.getStringWidth(cells.get(r)[c]) / 1000 * SIZE + 2 * pad);
                for (String word : cells.get(r)[c].split(" ")) {
                    minimum[c] = Math.max(minimum[c], font.getStringWidth(word) / 1000 * SIZE + 2 * pad + 1);
                }
            }
        }
        float[] widths = natural.clone();
        if (total(natural) > width) {
            float extra = width - total(minimum);
            if (extra < 0) throw new IllegalStateException("table too wide for the page");
            float stretch = total(natural) - total(minimum);
            for (int c = 0; c < columns; c++) widths[c] = minimum[c] + extra * (natural[c] - minimum[c]) / stretch;
        }
        float top = y + SIZE;
        float cursor = top;
        content.setLineWidth(0.6f);
        hline(content, MARGIN, total(widths), cursor);
        for (int r = 0; r < cells.size(); r++) {
            PDType1Font font = r == 0 ? bold : regular;
            List<List<String>> wrapped = new ArrayList<>();
            int height = 1;
            for (int c = 0; c < columns; c++) {
                String text = c < cells.get(r).length ? cells.get(r)[c] : "";
                List<String> lines = wrap(text, font, SIZE, widths[c] - 2 * pad);
                wrapped.add(lines);
                height = Math.max(height, lines.size());
            }
            float x = MARGIN;
            for (int c = 0; c < columns; c++) {
                float baseline = cursor - SIZE - pad + 2;
                for (String text : wrapped.get(c)) {
                    content.beginText();
                    content.setFont(font, SIZE);
                    content.newLineAtOffset(x + pad, baseline);
                    content.showText(text);
                    content.endText();
                    baseline -= LEADING;
                }
                x += widths[c];
            }
            cursor -= height * LEADING + 2 * pad - (LEADING - SIZE);
            hline(content, MARGIN, total(widths), cursor);
        }
        float x = MARGIN;
        for (int c = 0; c <= columns; c++) {
            content.moveTo(x, top);
            content.lineTo(x, cursor);
            content.stroke();
            if (c < columns) x += widths[c];
        }
        return cursor - SIZE - 6;
    }

    private static void hline(PDPageContentStream content, float x, float length, float y) throws IOException {
        content.moveTo(x, y);
        content.lineTo(x + length, y);
        content.stroke();
    }

    private static float total(float[] widths) {
        float sum = 0;
        for (float w : widths) sum += w;
        return sum;
    }

    /**
     * An image-only copy of a PDF, as a scanner would produce it: every page rendered to a grey bitmap and stored as a
     * JPEG, no text layer at all (ADR-0032 OCR measurement). Rendering uses the host's fonts for the standard 14 fonts,
     * so the bytes differ between machines: generated on demand, never committed.
     */
    public static byte[] scanned(byte[] pdf, float dpi) {
        try (PDDocument source = org.apache.pdfbox.Loader.loadPDF(pdf); PDDocument out = new PDDocument()) {
            org.apache.pdfbox.rendering.PDFRenderer renderer = new org.apache.pdfbox.rendering.PDFRenderer(source);
            for (int i = 0; i < source.getNumberOfPages(); i++) {
                java.awt.image.BufferedImage image = renderer.renderImageWithDPI(i, dpi,
                        org.apache.pdfbox.rendering.ImageType.GRAY);
                PDRectangle box = source.getPage(i).getMediaBox();
                PDPage page = new PDPage(box);
                out.addPage(page);
                var picture = org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(out, image, 0.8f);
                try (PDPageContentStream content = new PDPageContentStream(out, page)) {
                    content.drawImage(picture, 0, 0, box.getWidth(), box.getHeight());
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static float line(PDPageContentStream content, PDType1Font font, float size, String text, float y)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(MARGIN, y);
        content.showText(text);
        content.endText();
        return y - LEADING;
    }

    private static List<String> wrap(String paragraph, PDType1Font font, float size, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : paragraph.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (font.getStringWidth(candidate) / 1000 * size > width && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    private static List<List<String>> pages(String source) {
        List<List<String>> pages = new ArrayList<>();
        List<String> page = new ArrayList<>();
        for (String raw : source.replace("\r", "").split("\n")) {
            if (raw.equals("---")) {
                pages.add(page);
                page = new ArrayList<>();
            } else {
                page.add(raw.strip());
            }
        }
        pages.add(page);
        return pages;
    }

    private static byte[] md5(String name) {
        try {
            return MessageDigest.getInstance("MD5").digest(name.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
