package com.verso.qa.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounded multi-step reasoning (ADR-0048 phase 5, ADR-0055): a comparison or multi-part question is split by one chat
 * call into at most three standalone sub-questions; each is searched like a question (hybrid search, access filter,
 * answerability gate); one synthesis call answers over the passages grouped per sub-question. Off until measured on
 * the multi-hop set (eval/multihop-set.json); every failure falls back to the single-pass answer.
 *
 * @param enabled              off: every question is answered in a single pass, as before ADR-0055
 * @param maxSubQuestions      hard cap on sub-questions (2 or 3); a decomposition with more is rejected, not cut
 * @param maxSubQuestionChars  a longer sub-question is not a standalone search question: the decomposition is rejected
 * @param passagesPerStep      at most this many relevant passages of one sub-question go to the synthesis prompt
 * @param maxPassages          at most this many passages in the synthesis prompt overall (shared out per sub-question)
 * @param entityGate           the gate also counts SYSTEM and CODE entities of the system model the question names
 * @param decomposeTimeout     the decomposition call; after it the question is answered in a single pass
 * @param timeout              the whole multi-step preparation (decomposition and sub-question searches); after it the
 *                             question is answered in a single pass from the passages already retrieved
 */
@ConfigurationProperties("verso.qa.reasoning")
public record QaReasoningProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("3") int maxSubQuestions,
        @DefaultValue("200") int maxSubQuestionChars,
        @DefaultValue("3") int passagesPerStep,
        @DefaultValue("6") int maxPassages,
        @DefaultValue("true") boolean entityGate,
        @DefaultValue("10s") Duration decomposeTimeout,
        @DefaultValue("20s") Duration timeout) {

    /** ADR-0048: never more than three sub-questions, whatever the configuration says. */
    public static final int HARD_CAP = 3;

    public QaReasoningProperties {
        if (maxSubQuestions < 2 || maxSubQuestions > HARD_CAP) {
            throw new IllegalStateException("verso.qa.reasoning.max-sub-questions must be 2 or 3");
        }
        if (maxSubQuestionChars < 40 || maxSubQuestionChars > 300) {
            throw new IllegalStateException("verso.qa.reasoning.max-sub-question-chars must be between 40 and 300");
        }
        if (passagesPerStep < 1) throw new IllegalStateException("verso.qa.reasoning.passages-per-step must be positive");
        if (maxPassages < maxSubQuestions || maxPassages > 10) {
            throw new IllegalStateException(
                    "verso.qa.reasoning.max-passages must be between max-sub-questions and 10");
        }
        if (decomposeTimeout == null || !decomposeTimeout.isPositive()) {
            throw new IllegalStateException("verso.qa.reasoning.decompose-timeout must be positive");
        }
        if (timeout == null || timeout.compareTo(decomposeTimeout) < 0) {
            throw new IllegalStateException("verso.qa.reasoning.timeout must be at least decompose-timeout");
        }
    }

    /** Passages one sub-question may bring when {@code steps} share the prompt. */
    public int passagesFor(int steps) {
        return Math.max(1, Math.min(passagesPerStep, maxPassages / Math.max(1, steps)));
    }
}
