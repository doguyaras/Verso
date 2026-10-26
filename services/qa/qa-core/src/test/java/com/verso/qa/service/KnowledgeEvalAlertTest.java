package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.config.QaKnowledgeProperties;
import org.junit.jupiter.api.Test;

/** The golden-set alert (ADR-0049): a recall drop beyond alert-drop against the baseline, or source drift. */
class KnowledgeEvalAlertTest {

    private static final QaKnowledgeProperties.Eval EVAL = KnowledgeCardRulesTest.eval();

    @Test
    void alert_whenRecallFallsMoreThanTheDrop() {
        assertThat(KnowledgeEval.alert(0.90, 0.95, 0.0, EVAL)).isTrue();
        assertThat(KnowledgeEval.alert(0.93, 0.95, 0.0, EVAL)).as("exactly the drop is not an alert").isFalse();
        assertThat(KnowledgeEval.alert(0.99, 0.95, 0.0, EVAL)).as("a rise").isFalse();
    }

    @Test
    void alert_whenSourceDriftPassesItsLimit() {
        assertThat(KnowledgeEval.alert(1.0, 1.0, 0.11, EVAL)).isTrue();
        assertThat(KnowledgeEval.alert(1.0, 1.0, 0.10, EVAL)).isFalse();
    }

    @Test
    void noAlert_withoutAComparison() {
        assertThat(KnowledgeEval.alert(null, 0.95, null, EVAL)).as("nothing comparable").isFalse();
        assertThat(KnowledgeEval.alert(0.5, null, 0.0, EVAL)).as("first run: no baseline").isFalse();
    }
}
