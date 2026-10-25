package com.verso.qa.service;

import com.verso.document.api.dto.RetrievedPassage;
import com.verso.qa.api.dto.UnverifiedSentence;
import com.verso.qa.config.QaGroundingProperties;
import com.verso.qa.config.QaProperties;
import com.verso.qa.service.SentenceSplitter.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Answer groundedness (ADR-0047), after {@link CitationExtractor}: each answer sentence that carries a citation marker
 * is checked against the passages it cites ({@link AnchorCheck}). The answer is never changed; the result only says
 * which sentences could not be verified. Runs on both endpoints in the same place, before the {@code citations} event
 * of the stream. Nothing of the answer, the passages or the question is logged here (llm-rules 2.1).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GroundingCheck {

    // The cleaned answer carries only validated markers, one number each: CitationExtractor rewrote "[1, 3]" as
    // "[1][3]" and removed the numbers it could not map.
    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,9})]");

    /**
     * {@code unverified} in answer order, or null when the check is off; {@code cited} sentences carry a marker,
     * {@code checked} of them state an anchor (number, number word or code).
     */
    public record Grounding(List<UnverifiedSentence> unverified, int cited, int checked) {

        /** Every cited sentence was checked and none is supported: the answer has no verified source. */
        public boolean noneVerified() {
            return unverified != null && cited > 0 && unverified.size() == cited;
        }
    }

    private final QaGroundingProperties grounding;
    private final QaProperties properties;

    public GroundingCheck(QaGroundingProperties grounding, QaProperties properties) {
        this.grounding = grounding;
        this.properties = properties;
    }

    /**
     * @param answer   the answer as {@link CitationExtractor} returned it (validated markers only)
     * @param passages the passages the prompt numbered, in order ({@code [n]} is {@code passages.get(n - 1)})
     * @param question the question as asked: a number the asker wrote may be repeated anywhere in the answer
     */
    public Grounding check(String answer, List<RetrievedPassage> passages, String question) {
        if (!grounding.enabled()) return new Grounding(null, 0, 0);
        List<Span> sentences = SentenceSplitter.split(answer, true);
        if (sentences.isEmpty()) return new Grounding(List.of(), 0, 0);
        List<List<Integer>> numbers = new ArrayList<>(sentences.size());
        for (int i = 0; i < sentences.size(); i++) numbers.add(new ArrayList<>());
        Matcher marker = MARKER.matcher(answer);
        while (marker.find()) {
            int number = Integer.parseInt(marker.group(1));
            if (number < 1 || number > passages.size()) continue;
            List<Integer> own = numbers.get(sentenceOf(answer, sentences, marker.start()));
            if (!own.contains(number)) own.add(number);
        }
        List<UnverifiedSentence> unverified = new ArrayList<>();
        int cited = 0;
        int checked = 0;
        for (int i = 0; i < sentences.size(); i++) {
            List<Integer> cites = numbers.get(i);
            if (cites.isEmpty()) continue;
            cited++;
            Span span = sentences.get(i);
            String text = MARKER.matcher(answer.substring(span.start(), span.end())).replaceAll(" ");
            List<String> evidence = new ArrayList<>(cites.size());
            for (int n : cites) evidence.add(promptPart(passages.get(n - 1).content()));
            AnchorCheck.Result result = AnchorCheck.check(text, evidence, question);
            if (result.checked() == 0) continue;
            checked++;
            if (result.unverified()) unverified.add(new UnverifiedSentence(span.start(), span.end(), List.copyOf(cites)));
        }
        return new Grounding(List.copyOf(unverified), cited, checked);
    }

    /** The same part of the passage the prompt carried (PromptBuilder): the model cannot rely on the rest. */
    private String promptPart(String content) {
        if (content == null) return "";
        return content.length() <= properties.maxPassageChars() ? content : content.substring(0, properties.maxPassageChars());
    }

    /** The sentence a marker belongs to; one that opens its sentence ("… gündür. [1] Ayrıca") belongs to the one before. */
    private static int sentenceOf(String answer, List<Span> sentences, int position) {
        int index = 0;
        while (index + 1 < sentences.size() && sentences.get(index + 1).start() <= position) index++;
        Span sentence = sentences.get(index);
        String before = answer.substring(sentence.start(), Math.max(sentence.start(), position));
        if (index > 0 && MARKER.matcher(before).replaceAll("").isBlank()) index--;
        return index;
    }
}
