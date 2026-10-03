package com.verso.document.worker;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.config.DocumentProperties;
import com.verso.document.worker.TextSections.Block;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Sections of a Word file (Office Open XML, ADR-0016), read with the JDK alone: the file is a ZIP, the text is in the
 * part {@code word/document.xml}. The input is untrusted and parsed inside the API's JVM, so (as for PDF, ADR-0011):
 *
 * <ul>
 *   <li>the ZIP is read as a stream from memory, never written anywhere; at most {@link #MAX_ENTRIES} parts, and
 *       every part is inflated through one byte budget (twice max-content-bytes), so a ZIP bomb costs bounded CPU
 *       and the main part cannot exceed max-content-bytes;</li>
 *   <li>the XML parser is the JDK's StAX reader with DTDs and external entities switched off (XXE, entity
 *       expansion); it reads iteratively, so deep nesting does not recurse;</li>
 *   <li>text is counted while it is collected and refused above max-text-chars.</li>
 * </ul>
 *
 * Read: paragraphs, tabs, line breaks, tables (one block per row, cells joined by " | "); a paragraph styled as a
 * heading (Heading n, Başlık n, Title, or an outline level) starts a section. Not read: headers, footers, footnotes,
 * comments, deleted revisions and field codes. A password-protected DOCX is not a ZIP and is refused at upload.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocxTextExtractor {

    static final String MAIN_PART = "word/document.xml";
    /** A Word file has a few dozen parts; many images can make it a few hundred. */
    static final int MAX_ENTRIES = 1000;
    private static final Set<String> WORD_NAMESPACES = Set.of(
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
            "http://purl.oclc.org/ooxml/wordprocessingml/main");
    /** Style ids Word writes for heading styles; Turkish Word turns "Başlık 1" into "Balk1". */
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)(heading|balk|başlık|title|konubal)\\s*\\d*");

    private final DocumentProperties properties;

    public DocxTextExtractor(DocumentProperties properties) {
        this.properties = properties;
    }

    public List<String> extract(byte[] docx) {
        return TextSections.split(blocks(docx), properties);
    }

    List<Block> blocks(byte[] docx) {
        long budget = properties.maxContentBytes().toBytes() * 2;
        List<Block> blocks = null;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++entries > MAX_ENTRIES) throw rejected(DocumentFailureReason.UNSUPPORTED_FILE);
                Budget part = new Budget(zip, budget, MAIN_PART.equals(entry.getName())
                        ? properties.maxContentBytes().toBytes() : Long.MAX_VALUE);
                if (MAIN_PART.equals(entry.getName())) {
                    // Two main parts would leave it open which one Word shows: refuse instead of guessing.
                    if (blocks != null) throw rejected(DocumentFailureReason.UNSUPPORTED_FILE);
                    blocks = parse(part);
                }
                part.drain();
                budget -= part.read;
            }
        } catch (IngestionRejectedException e) {
            throw e;
        } catch (IOException | XMLStreamException | RuntimeException e) {
            // Not a ZIP, a damaged or encrypted entry, or XML that is not well formed: never the parser's message.
            throw rejected(DocumentFailureReason.INVALID_FILE);
        }
        if (blocks == null) throw rejected(DocumentFailureReason.INVALID_FILE);
        return blocks;
    }

    private List<Block> parse(InputStream xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        XMLStreamReader reader = factory.createXMLStreamReader(xml);
        Collector out = new Collector(properties.maxTextChars());
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && word(reader)) out.start(reader);
                else if (event == XMLStreamConstants.END_ELEMENT && word(reader)) out.end(reader.getLocalName());
                else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && out.inText) {
                    out.text(reader.getText());
                }
            }
        } finally {
            reader.close();
        }
        return out.blocks;
    }

    private static boolean word(XMLStreamReader reader) {
        return WORD_NAMESPACES.contains(reader.getNamespaceURI());
    }

    private static IngestionRejectedException rejected(DocumentFailureReason reason) {
        return new IngestionRejectedException(reason);
    }

    /** Walks the WordprocessingML elements that carry text; everything else is skipped. */
    private static final class Collector {
        private final List<Block> blocks = new ArrayList<>();
        private final long maxChars;
        private final StringBuilder paragraph = new StringBuilder();
        private final StringBuilder cell = new StringBuilder();
        private final List<String> row = new ArrayList<>();
        private long chars;
        private int tableDepth;
        private int paragraphDepth;
        private boolean heading;
        private boolean inText;

        Collector(long maxChars) {
            this.maxChars = maxChars;
        }

        void start(XMLStreamReader reader) {
            switch (reader.getLocalName()) {
                case "p" -> {
                    // A text box holds paragraphs inside a paragraph's run: its text joins the outer paragraph.
                    if (paragraphDepth++ == 0) {
                        paragraph.setLength(0);
                        heading = false;
                    }
                }
                case "pStyle" -> heading |= HEADING_STYLE.matcher(attribute(reader, "val")).matches();
                case "outlineLvl" -> heading = true;
                case "t" -> inText = true;
                case "tab" -> append("\t");
                case "br", "cr" -> append("\n");
                case "tbl" -> tableDepth++;
                case "tr" -> {
                    if (tableDepth == 1) row.clear();
                }
                case "tc" -> {
                    if (tableDepth == 1) cell.setLength(0);
                }
                default -> { }
            }
        }

        void end(String name) {
            switch (name) {
                case "t" -> inText = false;
                case "p" -> {
                    if (--paragraphDepth > 0) {
                        paragraph.append(' ');
                        return;
                    }
                    String text = paragraph.toString();
                    if (tableDepth > 0) {
                        if (!cell.isEmpty() && !text.isBlank()) cell.append(' ');
                        cell.append(text);
                    } else if (!text.isBlank()) {
                        blocks.add(new Block(text, heading));
                    }
                    paragraph.setLength(0);
                }
                case "tc" -> {
                    if (tableDepth == 1) row.add(cell.toString().strip());
                }
                case "tr" -> {
                    if (tableDepth == 1 && row.stream().anyMatch(c -> !c.isEmpty())) {
                        blocks.add(new Block(String.join(" | ", row), false));
                    }
                }
                case "tbl" -> tableDepth--;
                default -> { }
            }
        }

        void text(String text) {
            append(text);
        }

        private void append(String text) {
            chars += text.length();
            if (chars > maxChars) throw rejected(DocumentFailureReason.TOO_MUCH_TEXT);
            paragraph.append(text);
        }

        private static String attribute(XMLStreamReader reader, String localName) {
            for (int i = 0; i < reader.getAttributeCount(); i++) {
                if (localName.equals(reader.getAttributeLocalName(i))) return reader.getAttributeValue(i);
            }
            return "";
        }
    }

    /**
     * The current ZIP entry, inflated through two limits: what is left of the whole file's byte budget (ZIP bombs in
     * any part) and the part's own limit (the main part's XML). Draining a part counts too: skipping it inflates it.
     */
    private static final class Budget extends InputStream {
        private final InputStream in;
        private final long total;
        private final long partLimit;
        long read;

        Budget(InputStream in, long total, long partLimit) {
            this.in = in;
            this.total = total;
            this.partLimit = partLimit;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) {
                read += n;
                if (read > partLimit) throw rejected(DocumentFailureReason.TOO_MUCH_TEXT);
                if (read > total) throw rejected(DocumentFailureReason.UNSUPPORTED_FILE);
            }
            return n;
        }

        void drain() throws IOException {
            byte[] buffer = new byte[8192];
            while (read(buffer, 0, buffer.length) >= 0) {
                // counting only
            }
        }

        @Override
        public void close() {
            // the ZIP stream stays open for the next entry
        }
    }
}
