package com.verso.document.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.config.DocumentProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Splits page texts into overlapping chunks (llm-rules 6.3, 6.4). A chunk never spans two pages, so every citation is
 * one page. Sizes are characters, not tokens: Turkish is agglutinative and a foreign tokenizer would miscount. A cut
 * moves back to the last whitespace in the second half of the window, so words are not split when avoidable.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PageChunker {

    /** A chunk and where it comes from; chunkIndex runs over the whole document. */
    public record Chunk(int pageNumber, int chunkIndex, String content) {
    }

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
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(text.length(), start + size);
                if (end < text.length()) {
                    int space = lastWhitespace(text, start + size / 2, end);
                    if (space > start) end = space;
                }
                String content = text.substring(start, end).strip();
                if (!content.isEmpty()) chunks.add(new Chunk(p + 1, chunks.size(), content));
                if (end >= text.length()) break;
                start = Math.max(end - overlap, start + 1);
                // Start the next chunk at a word boundary inside the overlap.
                while (start < end && !Character.isWhitespace(text.charAt(start - 1))) start++;
            }
        }
        return chunks;
    }

    private static int lastWhitespace(String text, int from, int to) {
        for (int i = to; i > from; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) return i - 1;
        }
        return -1;
    }
}
