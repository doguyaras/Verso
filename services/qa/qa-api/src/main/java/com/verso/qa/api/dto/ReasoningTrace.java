package com.verso.qa.api.dto;

import java.util.List;

/**
 * How a multi-step answer was researched (ADR-0055, "Nasıl araştırıldı"): the standalone sub-questions the question
 * was split into, in order, whether the caller's documents held anything for each, and the contradictions between
 * cited passages that Verso already knew about. Only for the asker; neither stored nor logged.
 *
 * @param steps          two or three sub-questions, in the order they were searched
 * @param contradictions pairs of citations of this answer that a stored offline check (the contradiction scan of
 *                       ADR-0052 or the system model of ADR-0054) knows to conflict; empty when none is known. The model
 *                       never decides this.
 */
public record ReasoningTrace(List<Step> steps, List<ContradictionNote> contradictions) {

    public ReasoningTrace {
        steps = List.copyOf(steps);
        contradictions = List.copyOf(contradictions);
    }

    /**
     * One sub-question.
     *
     * @param number    1-based, in search order
     * @param question  the standalone sub-question (model text, validated; plain text for the client)
     * @param found     false: the caller's documents held nothing for it; the answer says so explicitly
     * @param citations the numbers of this answer's citations that came from this sub-question's passages
     */
    public record Step(int number, String question, boolean found, List<Integer> citations) {

        public Step {
            citations = List.copyOf(citations);
        }

        @Override
        public String toString() {
            return "Step[number=" + number + ", found=" + found + "]";
        }
    }

    /**
     * Two citations of the answer that a stored offline check knows to conflict ("Belgeler arasında çelişki").
     *
     * @param first  the lower citation number
     * @param second the higher citation number
     * @param source which stored check knows the conflict
     */
    public record ContradictionNote(int first, int second, ContradictionSource source) {
    }

    /** Where a contradiction note comes from; never from the model. */
    public enum ContradictionSource {
        /** A stored candidate of the knowledge-health contradiction scan (ADR-0052). */
        CONTRADICTION_SCAN,
        /** Two different definitions of the same term or code in the system model (ADR-0054). */
        SYSTEM_MODEL
    }
}
