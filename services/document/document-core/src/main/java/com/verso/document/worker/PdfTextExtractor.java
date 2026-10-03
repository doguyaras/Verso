package com.verso.document.worker;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.config.DocumentProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Page-by-page text of a PDF (ADR-0011, PDFBox 3). The input is untrusted and is parsed inside the API's JVM, so
 * every way a small file can claim a large amount of memory is bounded before it happens (phase 4 review C1/C2: a
 * 21 KB file used to exhaust the heap):
 *
 * <ul>
 *   <li>the parser's stream cache lives in a bounded amount of heap (no temp files; the container is read-only);</li>
 *   <li>the page count is checked before any content is read;</li>
 *   <li>the decompressed size of every content stream (pages and form XObjects) is measured with a byte-limited
 *       inflater before the text stripper decodes it; a stream in an encoding that cannot be measured that way is
 *       refused;</li>
 *   <li>glyphs are counted while the stripper collects them, per page and per document, so a page cannot pile up
 *       millions of text positions before a limit applies;</li>
 *   <li>content operators are counted and the graphics state stack is bounded: millions of "q" operators in a
 *       10 KB file used to grow the stack until the heap was gone (security review S1).</li>
 * </ul>
 *
 * Every way the file can be unusable ends in a fixed reason, never in the parser's message (llm-rules 2.1).
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PdfTextExtractor {

    /** Form XObjects may nest; deeper nesting than this is not a real document. */
    private static final int MAX_FORM_DEPTH = 10;
    /** Real documents nest a few dozen saved graphics states at most. */
    static final int MAX_GRAPHICS_STACK = 256;
    /** Content operators per document: a dense 500-page document needs well under a million. */
    static final long MAX_OPERATORS = 5_000_000;

    private final DocumentProperties properties;

    public PdfTextExtractor(DocumentProperties properties) {
        this.properties = properties;
    }

    /** Text of every page, index = page number - 1; a page without text is an empty string. */
    public List<String> extract(byte[] pdf) {
        MemoryUsageSetting memory = MemoryUsageSetting.setupMainMemoryOnly(properties.parserMemory().toBytes());
        try (PDDocument document = Loader.loadPDF(pdf, "", null, null, memory.streamCache)) {
            int pages = document.getNumberOfPages();
            if (pages > properties.maxPages()) throw rejected(DocumentFailureReason.TOO_MANY_PAGES);
            if (pages == 0) throw rejected(DocumentFailureReason.NO_TEXT);
            measureContent(document);
            BoundedTextStripper stripper = new BoundedTextStripper(properties.maxPageChars(), properties.maxTextChars());
            stripper.setSortByPosition(true);
            List<String> texts = new ArrayList<>(pages);
            long total = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalize(stripper.getText(document));
                total += text.length();
                if (total > properties.maxTextChars()) throw rejected(DocumentFailureReason.TOO_MUCH_TEXT);
                texts.add(text);
            }
            if (total == 0) throw rejected(DocumentFailureReason.NO_TEXT);
            return texts;
        } catch (InvalidPasswordException e) {
            throw rejected(DocumentFailureReason.ENCRYPTED);
        } catch (StackOverflowError e) {
            // Deeply nested structures recurse in the parser; the thread survives a stack overflow, the file is refused.
            throw rejected(DocumentFailureReason.UNSUPPORTED_PDF);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IngestionRejectedException rejected) throw rejected;
            // A malformed file can surface as any IOException or runtime exception inside the parser.
            throw rejected(DocumentFailureReason.NOT_A_PDF);
        }
    }

    /** Sums the decompressed size of all content streams the text stripper would decode; refuses above the limit. */
    private void measureContent(PDDocument document) throws IOException {
        long[] remaining = {properties.maxContentBytes().toBytes()};
        for (PDPage page : document.getPages()) {
            Iterator<PDStream> streams = page.getContentStreams();
            while (streams.hasNext()) measure(streams.next().getCOSObject(), remaining);
            measureForms(page.getResources(), remaining, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        }
    }

    private void measureForms(PDResources resources, long[] remaining, Set<COSStream> seen, int depth) throws IOException {
        if (resources == null) return;
        if (depth > MAX_FORM_DEPTH) throw rejected(DocumentFailureReason.UNSUPPORTED_PDF);
        for (COSName name : resources.getXObjectNames()) {
            if (resources.isImageXObject(name)) continue;
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDFormXObject form && seen.add(form.getCOSObject())) {
                measure(form.getCOSObject(), remaining);
                measureForms(form.getResources(), remaining, seen, depth + 1);
            }
        }
    }

    /** Only unfiltered and FlateDecode streams can be measured without decoding them into memory first. */
    private static void measure(COSStream stream, long[] remaining) throws IOException {
        COSBase filters = stream.getFilters();
        boolean flate;
        if (filters == null || (filters instanceof COSArray array && array.size() == 0)) {
            flate = false;
        } else if (COSName.FLATE_DECODE.equals(filters)
                || (filters instanceof COSArray array && array.size() == 1 && COSName.FLATE_DECODE.equals(array.get(0)))) {
            flate = true;
        } else {
            throw rejected(DocumentFailureReason.UNSUPPORTED_PDF);
        }
        if (!flate) {
            remaining[0] -= stream.getLength();
        } else {
            try (InputStream raw = stream.createRawInputStream();
                 InputStream inflated = new InflaterInputStream(raw, new Inflater(), 8192)) {
                byte[] buffer = new byte[8192];
                int read;
                while (remaining[0] >= 0 && (read = inflated.read(buffer)) != -1) remaining[0] -= read;
            }
        }
        if (remaining[0] < 0) throw rejected(DocumentFailureReason.TOO_MUCH_TEXT);
    }

    static String normalize(String text) {
        return TextNormalizer.normalize(text);
    }

    private static IngestionRejectedException rejected(DocumentFailureReason reason) {
        return new IngestionRejectedException(reason);
    }

    /** Counts glyphs as they are collected: the limit applies before the positions of a huge page fill the heap. */
    static final class BoundedTextStripper extends PDFTextStripper {
        private final int maxPageGlyphs;
        private final long maxGlyphs;
        private int pageGlyphs;
        private long glyphs;
        private long operators;

        BoundedTextStripper(int maxPageGlyphs, long maxGlyphs) {
            this.maxPageGlyphs = maxPageGlyphs;
            this.maxGlyphs = maxGlyphs;
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            pageGlyphs = 0;
            super.startPage(page);
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if (++operators > MAX_OPERATORS) throw rejected(DocumentFailureReason.TOO_MUCH_TEXT);
            super.processOperator(operator, operands);
            if (getGraphicsStackSize() > MAX_GRAPHICS_STACK) throw rejected(DocumentFailureReason.UNSUPPORTED_PDF);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (++pageGlyphs > maxPageGlyphs || ++glyphs > maxGlyphs) {
                throw rejected(DocumentFailureReason.TOO_MUCH_TEXT);
            }
            super.processTextPosition(text);
        }
    }
}
