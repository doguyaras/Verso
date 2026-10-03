package com.verso.qa.service;

import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.CitationUnit;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Server-side citations (llm-rules 3.3): the model only writes markers like {@code [2]} or {@code [1, 3]}; each number
 * is mapped to a passage that was really retrieved for this question. A number outside the list is removed from the
 * text and never becomes a citation, so the model cannot cite a document it was not given.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CitationExtractor {

    // [2], [ 2 ], [1, 3], [1; 3], [1-3]; full-width brackets are folded first (NFKC). Numbers of any length are matched
    // so that [1234] is removed too, not left in the text (phase 5 review L6).
    private static final String ITEM = "\\d{1,9}(?:\\s*[-–]\\s*\\d{1,9})?";
    private static final Pattern MARKER = Pattern.compile("\\[\\s*(" + ITEM + "(?:\\s*[,;]\\s*" + ITEM + ")*)\\s*]");
    private static final Pattern RANGE = Pattern.compile("\\s*[-–]\\s*");
    private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([.,;:!?])");

    /** The cleaned answer and its citations in order of first use. */
    public record Extracted(String answer, List<Citation> citations) {
    }

    public Extracted extract(String raw, List<RetrievedPassage> passages) {
        Map<Integer, Citation> used = new LinkedHashMap<>();
        Matcher matcher = MARKER.matcher(Normalizer.normalize(raw, Normalizer.Form.NFKC));
        StringBuilder answer = new StringBuilder();
        while (matcher.find()) {
            List<Integer> valid = new ArrayList<>();
            for (String part : matcher.group(1).split("[,;]")) {
                String[] bounds = RANGE.split(part.strip());
                long from = Long.parseLong(bounds[0]);
                long to = bounds.length == 2 ? Long.parseLong(bounds[1]) : from;
                if (from < 1 || to > passages.size() || from > to) continue;
                for (int number = (int) from; number <= to; number++) {
                    if (valid.contains(number)) continue;
                    valid.add(number);
                    RetrievedPassage passage = passages.get(number - 1);
                    used.putIfAbsent(number, new Citation(number, passage.documentId(), passage.fileName(),
                            passage.pageNumber(), unit(passage.unit())));
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

    /** Exhaustive on purpose: a new SourceUnit must not reach clients as a 500 (review L3). */
    private static CitationUnit unit(SourceUnit unit) {
        return switch (unit) {
            case PAGE -> CitationUnit.PAGE;
            case SECTION -> CitationUnit.SECTION;
        };
    }
}
