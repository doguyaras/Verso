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
    // Unicode white space (NBSP included) and control characters left over from text extraction become one space.
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{IsWhite_Space}\\p{Cc}]+");

    /** Upper bound of {@link Citation#excerpt()} in code points, the ellipsis included (API doc: at most 280). */
    static final int EXCERPT_MAX = 280;
    private static final String ELLIPSIS = "…";

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
                            passage.pageNumber(), unit(passage.unit()), excerpt(passage.content())));
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

    /**
     * The start of a passage for the client's hover card: white space collapsed, at most {@link #EXCERPT_MAX} code
     * points with a trailing ellipsis when cut. The cut goes back to the last space unless that would drop more than
     * half of the kept text (one very long word); it never splits a surrogate pair. Display only, never logged
     * (llm-rules 2.1).
     */
    static String excerpt(String content) {
        if (content == null) return "";
        String text = WHITESPACE.matcher(content).replaceAll(" ").strip();
        if (text.codePointCount(0, text.length()) <= EXCERPT_MAX) return text;
        int end = text.offsetByCodePoints(0, EXCERPT_MAX - ELLIPSIS.length());
        if (text.charAt(end) != ' ') {
            int space = text.lastIndexOf(' ', end - 1);
            if (space > end / 2) end = space;
        }
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    /** Exhaustive on purpose: a new SourceUnit must not reach clients as a 500 (review L3). */
    private static CitationUnit unit(SourceUnit unit) {
        return switch (unit) {
            case PAGE -> CitationUnit.PAGE;
            case SECTION -> CitationUnit.SECTION;
        };
    }
}
