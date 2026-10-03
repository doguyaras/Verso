package com.verso.document.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Synthetic Word files for tests (ADR-0016), built as a ZIP by hand: no Office library and no real document in the
 * repository. {@link #document(String)} wraps WordprocessingML body content in the minimal parts Word writes.
 */
public final class TestDocx {

    public static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private TestDocx() {}

    /** A paragraph of plain text. */
    public static String p(String text) {
        return "<w:p><w:r><w:t xml:space=\"preserve\">" + escape(text) + "</w:t></w:r></w:p>";
    }

    /** A paragraph in a heading style; Turkish Word writes the style id "Balk1" for "Başlık 1". */
    public static String heading(String styleId, String text) {
        return "<w:p><w:pPr><w:pStyle w:val=\"" + styleId + "\"/></w:pPr><w:r><w:t>" + escape(text) + "</w:t></w:r></w:p>";
    }

    /** A table, one array per row. */
    public static String table(String[]... rows) {
        StringBuilder xml = new StringBuilder("<w:tbl>");
        for (String[] row : rows) {
            xml.append("<w:tr>");
            for (String cell : row) xml.append("<w:tc>").append(p(cell)).append("</w:tc>");
            xml.append("</w:tr>");
        }
        return xml.append("</w:tbl>").toString();
    }

    /** A file with the given body content. */
    public static byte[] docx(String... body) {
        return document(String.join("", body));
    }

    public static byte[] document(String bodyXml) {
        return zip(Map.of("word/document.xml", documentXml(bodyXml)));
    }

    public static String documentXml(String bodyXml) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"" + W + "\"><w:body>" + bodyXml + "</w:body></w:document>";
    }

    /** A ZIP of the given parts, in order; content types and relationships first, as Word writes them. */
    public static byte[] zip(Map<String, String> parts) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        bytes.put("[Content_Types].xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns="
                + "\"http://schemas.openxmlformats.org/package/2006/content-types\"/>").getBytes(StandardCharsets.UTF_8));
        parts.forEach((name, xml) -> bytes.put(name, xml.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(bytes);
    }

    /** A ZIP of raw parts: for bombs and odd structures. Duplicate names are allowed (as entries, not as a map). */
    public static byte[] zipBytes(Map<String, byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Two entries with the same name; ZipOutputStream refuses that, so the second is renamed in the bytes. */
    public static byte[] twoMainParts(String firstBody, String secondBody) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("word/document.xml", documentXml(firstBody).getBytes(StandardCharsets.UTF_8));
        parts.put("word/documenX.xml", documentXml(secondBody).getBytes(StandardCharsets.UTF_8));
        byte[] zip = zipBytes(parts);
        String text = new String(zip, StandardCharsets.ISO_8859_1).replace("word/documenX.xml", "word/document.xml");
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
