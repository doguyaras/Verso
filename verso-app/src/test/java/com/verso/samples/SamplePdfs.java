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

    public static byte[] build(String name, String source) {
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
                    for (String paragraph : pages.get(i)) {
                        if (paragraph.isBlank()) {
                            y -= LEADING / 2;
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
