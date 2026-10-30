package com.verso.qa.service;

import com.verso.qa.config.QaRelevanceProperties;
import com.verso.qa.service.QuestionSlot.Type;
import com.verso.qa.service.RefusalDetector.Segment;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Answer relevance and abstention (ADR-0064), after {@link CitationExtractor} and {@link GroundingCheck}: does the model
 * answer the question at all? Groundedness (ADR-0047) checks that what an answer says stands in its sources; nothing
 * checked that it is what was asked. On a corpus where the topic of a question is in the documents but the asked fact
 * is not, a small model answers with the nearest rule (who is the director now: the appointment rule) or refuses in
 * its own words and cites a passage anyway; both reached the reader as sourced answers.
 *
 * <p>In order, deterministic and lexical, no model call:
 * <ol>
 *   <li>Every part of the answer is a refusal ({@link RefusalDetector}): {@link Verdict#REFUSAL}.</li>
 *   <li>The answer opens with a refusal and goes on: {@link Verdict#REFUSAL_WITH_FILLER} unless the rest gives the
 *       asked slot ({@link QuestionSlot}); a "ne kadar" question whose rest states durations is still an answer,
 *       the model having refused too early. A question that asks several things keeps its partial answer, and so
 *       does a yes/no question ("… mı?"): nothing tells filler from answer there.</li>
 *   <li>Otherwise, for a question asking one checkable thing, the answer must give its slot and, for a quantity, a
 *       cited passage must state some quantity: {@link Verdict#SLOT_MISSING}.</li>
 * </ol>
 * The caller turns every verdict but {@link Verdict#ANSWERS} into the fixed "not found" answer. Nothing of the
 * question, the answer or the passages is logged here (llm-rules 2.1).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AnswerRelevance {

    /** What the check found; all but {@link #ANSWERS} mean the documents were not shown to answer the question. */
    public enum Verdict {
        ANSWERS, REFUSAL, REFUSAL_WITH_FILLER, SLOT_MISSING;

        /** The fixed lower-case word for logs and metrics. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The verdict and the question type it was judged by (a fixed word, no content). */
    public record Result(Verdict verdict, String questionType) {

        static final Result OFF = new Result(Verdict.ANSWERS, "off");

        public boolean answered() {
            return verdict == Verdict.ANSWERS;
        }
    }

    private final QaRelevanceProperties properties;

    public AnswerRelevance(QaRelevanceProperties properties) {
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * @param question       the question as asked
     * @param answer         the answer as {@link CitationExtractor} returned it (validated markers only)
     * @param citedPassages  the prompt part of every cited passage; empty when the answer cites none
     * @param sourced        the answer would be a sourced answer (ANSWERED): only then is its slot checked; an
     *                       uncited or unverified answer is flagged already
     * @param multiStep      a multi-step synthesis (ADR-0055), whose parts answer different sub-questions: only a
     *                       refusal through and through is judged
     */
    public Result check(String question, String answer, List<String> citedPassages, boolean sourced,
                        boolean multiStep) {
        if (!properties.enabled()) return Result.OFF;
        Type type = QuestionSlot.typeOf(question);
        String typeTag = type.name().toLowerCase(Locale.ROOT);
        List<Segment> segments = RefusalDetector.segments(answer);
        if (segments.isEmpty()) return new Result(Verdict.ANSWERS, typeTag);
        if (segments.stream().allMatch(Segment::refusal)) return new Result(Verdict.REFUSAL, typeTag);
        // A question that asks several things may get a partial answer: one part stated missing, another answered.
        if (multiStep || QuestionSlot.multiPart(question)) return new Result(Verdict.ANSWERS, typeTag);
        String content = content(segments);
        if (segments.getFirst().refusal()) {
            // A yes/no question has no slot to tell filler from a late answer, and its rest often is the answer
            // (measured: "… aynı mı?" answered after "doğrudan bilgi bulunmamaktadır. Ancak, …" was right).
            if (QuestionSlot.yesNo(question)) return new Result(Verdict.ANSWERS, typeTag);
            boolean rescued = QuestionSlot.checkable(type, question) && QuestionSlot.filledBy(type, question, content);
            return new Result(rescued ? Verdict.ANSWERS : Verdict.REFUSAL_WITH_FILLER, typeTag);
        }
        if (!sourced) return new Result(Verdict.ANSWERS, typeTag);
        boolean filled = QuestionSlot.filledBy(type, question, content)
                // The asked quantity must come from a source: a cited passage states one.
                && (type != Type.NUMBER || citedPassages.isEmpty() || QuestionSlot.anyNumber(citedPassages));
        return new Result(filled ? Verdict.ANSWERS : Verdict.SLOT_MISSING, typeTag);
    }

    private static String content(List<Segment> segments) {
        StringBuilder text = new StringBuilder();
        for (Segment segment : segments) {
            if (!segment.refusal()) text.append(segment.text()).append(' ');
        }
        return text.toString().strip();
    }
}
