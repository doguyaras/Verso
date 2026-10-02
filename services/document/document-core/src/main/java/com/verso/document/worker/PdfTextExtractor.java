package com.verso.document.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.config.DocumentProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Page-by-page text of a PDF (ADR-0011, PDFBox 3). The input is untrusted: the parser works in a bounded amount of
 * heap (no temp files, the container is read-only), the page count is checked before any text is extracted, and the
 * extracted text is capped. Every way the file can be unusable ends in a fixed reason, never in the parser's message.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PdfTextExtractor {

    /** Control characters except tab and newline; PostgreSQL text cannot hold NUL at all. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t\\n]]");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n+");

    private final DocumentProperties properties;

    public PdfTextExtractor(DocumentProperties properties) {
        this.properties = properties;
    }

    /** Text of every page, index = page number - 1; a page without text is an empty string. */
    public List<String> extract(byte[] pdf) {
        MemoryUsageSetting memory = MemoryUsageSetting.setupMainMemoryOnly(properties.parserMemory().toBytes());
        try (PDDocument document = Loader.loadPDF(pdf, "", null, null, memory.streamCache)) {
            int pages = document.getNumberOfPages();
            if (pages > properties.maxPages()) throw new IngestionRejectedException(DocumentFailureReason.TOO_MANY_PAGES);
            if (pages == 0) throw new IngestionRejectedException(DocumentFailureReason.NO_TEXT);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<String> texts = new ArrayList<>(pages);
            long total = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalize(stripper.getText(document));
                total += text.length();
                if (total > properties.maxTextChars()) {
                    throw new IngestionRejectedException(DocumentFailureReason.TOO_MUCH_TEXT);
                }
                texts.add(text);
            }
            if (total == 0) throw new IngestionRejectedException(DocumentFailureReason.NO_TEXT);
            return texts;
        } catch (InvalidPasswordException e) {
            throw new IngestionRejectedException(DocumentFailureReason.ENCRYPTED);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IngestionRejectedException rejected) throw rejected;
            // A malformed file can surface as any IOException or runtime exception inside the parser.
            throw new IngestionRejectedException(DocumentFailureReason.NOT_A_PDF);
        }
    }

    static String normalize(String text) {
        String cleaned = CONTROL.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll(" ");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ");
        cleaned = BLANK_LINES.matcher(cleaned).replaceAll("\n\n");
        return cleaned.strip();
    }
}
