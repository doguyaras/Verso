package com.verso.document.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.config.DocumentProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Splits page texts into overlapping chunks (llm-rules 6.3, 6.4). A chunk never spans two pages, so every citation is
 * one page. Sizes are characters, not tokens: Turkish is agglutinative and a foreign tokenizer would miscount. A cut
 * moves back to the last whitespace in the second half of the window, so words are not split when avoidable.
 *
 * <p>Markdown tables (only the docling sidecar writes them, ADR-0032) are not cut like prose: a table that fits is one
 * chunk; a longer one is split between rows and every part repeats the header row, so a row is never read without
 * its column names. The line before the table (its heading or caption) goes in front of every part when it is short.
 * A page without a Markdown table is chunked exactly as before.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PageChunker {

    /** A chunk and where it comes from; chunkIndex runs over the whole document. */
    public record Chunk(int pageNumber, int chunkIndex, String content) {
    }

    /** GFM delimiter row: | --- | :--: | ---: | */
    private static final Pattern SEPARATOR = Pattern.compile("\\|(\\s*:?-{3,}:?\\s*\\|)+");
    /** Longer lines are not a heading or caption but a paragraph; they are not repeated per table part. */
    static final int MAX_CONTEXT_CHARS = 200;

    private final int size;
    private final int overlap;

    public PageChunker(DocumentProperties properties) {
        this.size = properties.chunkSize();
        this.overlap = properties.chunkOverlap();
    }

    public List<Chunk> chunk(List<String> pages) {
        List<Chunk> chunks = new ArrayList<>();
        for (int p = 0; p < pages.size(); p++) {
            String text = pages.get(p);
            if (!text.contains("|")) {
                prose(text, p + 1, chunks);
                continue;
            }
            String[] lines = text.split("\n", -1);
            StringBuilder prose = new StringBuilder();
            int i = 0;
            while (i < lines.length) {
                if (tableStartsAt(lines, i)) {
                    String head = lines[i].strip() + "\n" + lines[i + 1].strip();
                    String context = lastLine(prose);
                    if (context != null && context.length() + 1 + head.length() > size / 2) context = null;
                    // A heading alone before the table is not a chunk of its own: it travels with every table part.
                    if (context == null || !prose.toString().strip().equals(context)) prose(prose.toString(), p + 1, chunks);
                    prose.setLength(0);
                    int end = i + 2;
                    while (end < lines.length && isRow(lines[end])) end++;
                    table(context, head, List.of(lines).subList(i + 2, end), p + 1, chunks);
                    i = end;
                } else {
                    if (!prose.isEmpty()) prose.append('\n');
                    prose.append(lines[i]);
                    i++;
                }
            }
            prose(prose.toString(), p + 1, chunks);
        }
        return chunks;
    }

    /** The character window of the original chunker. */
    private void prose(String text, int page, List<Chunk> chunks) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + size);
            if (end < text.length()) {
                int space = lastWhitespace(text, start + size / 2, end);
                if (space > start) end = space;
            }
            String content = text.substring(start, end).strip();
            if (!content.isEmpty()) chunks.add(new Chunk(page, chunks.size(), content));
            if (end >= text.length()) break;
            start = Math.max(end - overlap, start + 1);
            // Start the next chunk at a word boundary inside the overlap.
            while (start < end && !Character.isWhitespace(text.charAt(start - 1))) start++;
        }
    }

    /** Whole when it fits; otherwise row groups, each with the header (and the context line while it fits). */
    private void table(String context, String head, List<String> rows, int page, List<Chunk> chunks) {
        String prefix = context != null ? context + "\n" + head : head;
        StringBuilder current = new StringBuilder(prefix);
        int rowsInCurrent = 0;
        for (String raw : rows) {
            String row = raw.strip();
            if (rowsInCurrent > 0 && current.length() + 1 + row.length() > size) {
                chunks.add(new Chunk(page, chunks.size(), current.toString()));
                current = new StringBuilder(prefix);
                rowsInCurrent = 0;
            }
            if (current.length() + 1 + row.length() > size) {
                // One row longer than a chunk: it is cut like prose; the header goes with its first part.
                prose(current + "\n" + row, page, chunks);
                current = new StringBuilder(prefix);
                rowsInCurrent = 0;
                continue;
            }
            current.append('\n').append(row);
            rowsInCurrent++;
        }
        if (rowsInCurrent > 0 || rows.isEmpty()) chunks.add(new Chunk(page, chunks.size(), current.toString()));
    }

    private static boolean tableStartsAt(String[] lines, int i) {
        return i + 1 < lines.length && isRow(lines[i]) && SEPARATOR.matcher(lines[i + 1].strip()).matches();
    }

    private static boolean isRow(String line) {
        String s = line.strip();
        return s.length() >= 2 && s.charAt(0) == '|' && s.charAt(s.length() - 1) == '|';
    }

    /** The last non-empty line of the prose before a table, when it is short enough to be its heading or caption. */
    private static String lastLine(StringBuilder prose) {
        String text = prose.toString().strip();
        if (text.isEmpty()) return null;
        String line = text.substring(text.lastIndexOf('\n') + 1).strip();
        return line.isEmpty() || line.length() > MAX_CONTEXT_CHARS ? null : line;
    }

    private static int lastWhitespace(String text, int from, int to) {
        for (int i = to; i > from; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) return i - 1;
        }
        return -1;
    }
}
