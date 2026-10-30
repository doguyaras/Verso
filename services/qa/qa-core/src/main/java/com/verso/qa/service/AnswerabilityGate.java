package com.verso.qa.service;

import com.verso.document.api.dto.RetrievedPassage;
import com.verso.qa.config.QaProperties;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Decides before the chat call whether the retrieved passages can answer the question at all (llm-rules 3.4,
 * ADR-0022). Below the gate the model is not asked and the fixed "not found" answer goes out.
 *
 * <p>The decision reads the cosine similarity of the most similar passage, never the fused rank: a fused score says
 * which passage is best, not whether any is good enough. Answerable when that similarity reaches
 * {@code min-similarity}, or when it reaches the lower {@code lexical-min-similarity} and that passage also contains a
 * specific question term (in at most {@code specific-term-max-share} of the account's chunks). The second branch is
 * for short questions about a code or an abbreviation ("FEFO ne demek?"), whose embedding is weak although the passage
 * names exactly what was asked.
 */
@Component
// Web application only, like the rest of question answering (QuestionServiceImpl).
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AnswerabilityGate {

    /** Which branch let the question through; NONE means "not found" without a model call. */
    public enum Decision {
        SIMILARITY, SPECIFIC_TERM, NONE
    }

    private final QaProperties properties;

    public AnswerabilityGate(QaProperties properties) {
        this.properties = properties;
    }

    public Decision decide(List<RetrievedPassage> passages) {
        RetrievedPassage best = passages.stream().max(Comparator.comparingDouble(RetrievedPassage::similarity)).orElse(null);
        if (best == null) return Decision.NONE;
        if (best.similarity() >= properties.minSimilarity()) return Decision.SIMILARITY;
        if (best.similarity() >= properties.lexicalMinSimilarity()
                && best.specificTermShare() <= properties.specificTermMaxShare()) {
            return Decision.SPECIFIC_TERM;
        }
        return Decision.NONE;
    }

    /**
     * The passages for the prompt once the gate is open, in retrieval (fused) order so citation numbers follow it: the
     * most similar passage, which is the evidence the gate read, and every other one at or above
     * {@code passage-min-similarity}. Weaker passages were measured to give the model more to answer from when the
     * documents do not hold the answer (ADR-0022: "bulunamadı" 10 of 28 with all five passages, 17 of 28 with a 0.50
     * floor; ADR-0063: the floor moved to 0.55 once the context header raised on-topic cosines).
     */
    public List<RetrievedPassage> promptPassages(List<RetrievedPassage> passages) {
        RetrievedPassage best = passages.stream().max(Comparator.comparingDouble(RetrievedPassage::similarity)).orElse(null);
        return passages.stream().filter(p -> p == best || p.similarity() >= properties.passageMinSimilarity()).toList();
    }
}
