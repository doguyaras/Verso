package com.verso.document.worker;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.config.DocumentProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuts the text of a file without pages (DOCX, TXT, MD) into numbered sections, which then take the place of PDF
 * pages: stored per section, chunked per section and cited as "section n" (ADR-0016). A heading starts a new section,
 * so a section usually is one topic; a section is also closed when the next paragraph would make it longer than
 * {@link #TARGET_CHARS} (about one printed page), and a single paragraph longer than the page limit is cut at
 * whitespace. The same limits as for PDF pages apply: section count (max-pages), characters per section
 * (max-page-chars) and characters in total (max-text-chars).
 */
final class TextSections {

    /** About one printed page of Turkish text; keeps a section citation as precise as a page citation. */
    static final int TARGET_CHARS = 3000;

    /** One paragraph (or table row) of the source; a heading starts a new section. */
    record Block(String text, boolean heading) {
    }

    private TextSections() {}

    static List<String> split(List<Block> blocks, DocumentProperties limits) {
        int target = Math.min(TARGET_CHARS, limits.maxPageChars());
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        long total = 0;
        for (Block block : blocks) {
            String text = TextNormalizer.normalize(block.text());
            if (text.isEmpty()) continue;
            total += text.length();
            if (total > limits.maxTextChars()) throw new IngestionRejectedException(DocumentFailureReason.TOO_MUCH_TEXT);
            boolean full = !current.isEmpty() && current.length() + 2 + text.length() > target;
            if (!current.isEmpty() && (block.heading() || full)) close(current, sections, limits);
            while (text.length() > limits.maxPageChars()) {
                int cut = lastWhitespace(text, limits.maxPageChars());
                if (!current.isEmpty()) close(current, sections, limits);
                current.append(text, 0, cut);
                close(current, sections, limits);
                text = text.substring(cut).strip();
            }
            if (!current.isEmpty()) current.append("\n\n");
            current.append(text);
        }
        if (!current.isEmpty()) close(current, sections, limits);
        if (sections.isEmpty()) throw new IngestionRejectedException(DocumentFailureReason.NO_TEXT);
        return sections;
    }

    private static void close(StringBuilder current, List<String> sections, DocumentProperties limits) {
        if (sections.size() >= limits.maxPages()) throw new IngestionRejectedException(DocumentFailureReason.TOO_MANY_PAGES);
        sections.add(current.toString().strip());
        current.setLength(0);
    }

    /**
     * The last whitespace before {@code limit}, in its second half; otherwise the limit itself, moved back by one when
     * it would split a surrogate pair (review K5: half an emoji is not valid text).
     */
    static int lastWhitespace(String text, int limit) {
        for (int i = limit; i > limit / 2; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i;
        }
        return Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
    }
}
