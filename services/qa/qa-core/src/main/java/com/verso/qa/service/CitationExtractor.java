package com.verso.qa.service;

import com.verso.document.api.dto.RetrievedPassage;
import com.verso.qa.api.dto.Citation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Server-side citations (llm-rules 3.3): the model only writes markers like {@code [2]} or {@code [1, 3]}; each number
 * is mapped to a passage that was really retrieved for this question. A number outside the list is removed from the
 * text and never becomes a citation, so the model cannot cite a document it was not given.
 */
@Component
public class CitationExtractor {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3}(?:\\s*,\\s*\\d{1,3})*)]");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([.,;:!?])");

    /** The cleaned answer and its citations in order of first use. */
    public record Extracted(String answer, List<Citation> citations) {
    }

    public Extracted extract(String raw, List<RetrievedPassage> passages) {
        Map<Integer, Citation> used = new LinkedHashMap<>();
        Matcher matcher = MARKER.matcher(raw);
        StringBuilder answer = new StringBuilder();
        while (matcher.find()) {
            List<Integer> valid = new ArrayList<>();
            for (String part : matcher.group(1).split(",")) {
                int number = Integer.parseInt(part.strip());
                if (number >= 1 && number <= passages.size()) {
                    valid.add(number);
                    RetrievedPassage passage = passages.get(number - 1);
                    used.putIfAbsent(number, new Citation(number, passage.documentId(), passage.fileName(),
                            passage.pageNumber()));
                }
            }
            String replacement = valid.isEmpty() ? "" : valid.stream().map(n -> "[" + n + "]")
                    .reduce("", String::concat);
            matcher.appendReplacement(answer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(answer);
        String cleaned = SPACE_BEFORE_PUNCTUATION.matcher(answer.toString()).replaceAll("$1").strip();
        return new Extracted(cleaned, List.copyOf(used.values()));
    }
}
