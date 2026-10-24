package com.verso.qa.service;

import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.CitationUnit;
import com.verso.qa.config.QaProperties;
import com.verso.qa.service.SentenceSplitter.Span;
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

    private final QaProperties properties;

    public CitationExtractor(QaProperties properties) {
        this.properties = properties;
    }

    public Extracted extract(String raw, List<RetrievedPassage> passages) {
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        List<Span> sentences = SentenceSplitter.split(normalized, true);
        // Per cited number, in order of first use: the answer sentences that carry its marker (ADR-0019).
        Map<Integer, StringBuilder> citing = new LinkedHashMap<>();
        Matcher matcher = MARKER.matcher(normalized);
        StringBuilder answer = new StringBuilder();
        while (matcher.find()) {
            List<Integer> valid = new ArrayList<>();
            for (String part : matcher.group(1).split("[,;]")) {
                String[] bounds = RANGE.split(part.strip());
                long from = Long.parseLong(bounds[0]);
                long to = bounds.length == 2 ? Long.parseLong(bounds[1]) : from;
                if (from < 1 || to > passages.size() || from > to) continue;
                for (int number = (int) from; number <= to; number++) {
                    if (!valid.contains(number)) valid.add(number);
                }
            }
            if (!valid.isEmpty()) {
                String sentence = citingSentence(normalized, sentences, matcher.start());
                for (int number : valid) {
                    citing.computeIfAbsent(number, n -> new StringBuilder()).append(' ').append(sentence);
                }
            }
            String replacement = valid.stream().map(n -> "[" + n + "]").reduce("", String::concat);
            matcher.appendReplacement(answer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(answer);
        String cleaned = SPACE_BEFORE_PUNCTUATION.matcher(answer.toString()).replaceAll("$1").strip();
        List<Citation> citations = new ArrayList<>(citing.size());
        citing.forEach((number, text) -> {
            RetrievedPassage passage = passages.get(number - 1);
            // Evidence only from the part of the passage the prompt carried (PromptBuilder cuts at the same limit).
            String excerpt = ExcerptSelector.select(passage.content(), text.toString(), properties.maxPassageChars());
            citations.add(new Citation(number, passage.documentId(), passage.fileName(), passage.pageNumber(),
                    unit(passage.unit()), excerpt));
        });
        return new Extracted(cleaned, List.copyOf(citations));
    }

    /**
     * The answer sentence that a marker at {@code position} belongs to, markers removed. A marker that opens its
     * sentence ("… on dört gündür. [1] Ayrıca …") belongs to the sentence before it.
     */
    private static String citingSentence(String answer, List<Span> sentences, int position) {
        if (sentences.isEmpty()) return "";
        int index = 0;
        while (index + 1 < sentences.size() && sentences.get(index + 1).start() <= position) index++;
        Span sentence = sentences.get(index);
        String before = answer.substring(sentence.start(), Math.max(sentence.start(), position));
        if (index > 0 && MARKER.matcher(before).replaceAll("").isBlank()) sentence = sentences.get(index - 1);
        return MARKER.matcher(answer.substring(sentence.start(), sentence.end())).replaceAll(" ");
    }

    /** Exhaustive on purpose: a new SourceUnit must not reach clients as a 500 (review L3). */
    private static CitationUnit unit(SourceUnit unit) {
        return switch (unit) {
            case PAGE -> CitationUnit.PAGE;
            case SECTION -> CitationUnit.SECTION;
        };
    }
}
