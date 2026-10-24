package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.config.QaProperties;
import com.verso.qa.service.AnswerabilityGate.Decision;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** llm-rules 3.4, ADR-0022: the two branches of the gate and their edges. */
class AnswerabilityGateTest {

    private final AnswerabilityGate gate = new AnswerabilityGate(properties(0.47, 0.45, 0.045));

    @Test
    void decide_whenNothingWasRetrieved_isNone() {
        assertThat(gate.decide(List.of())).isEqualTo(Decision.NONE);
    }

    @Test
    void decide_whenTheBestSimilarityReachesTheThreshold_isSimilarityWhateverTheTerms() {
        assertThat(gate.decide(List.of(passage(0.47, 1.0)))).isEqualTo(Decision.SIMILARITY);
        assertThat(gate.decide(List.of(passage(0.80, 1.0)))).isEqualTo(Decision.SIMILARITY);
    }

    /** "FEFO ne demek?": a weak embedding, but the most similar passage names the rare term. */
    @Test
    void decide_whenBetweenTheFloorsAndTheBestPassageHasASpecificTerm_isSpecificTerm() {
        assertThat(gate.decide(List.of(passage(0.45, 0.045)))).isEqualTo(Decision.SPECIFIC_TERM);
        assertThat(gate.decide(List.of(passage(0.4699, 0.01)))).isEqualTo(Decision.SPECIFIC_TERM);
    }

    @Test
    void decide_whenBetweenTheFloorsButTheTermIsCommon_isNone() {
        assertThat(gate.decide(List.of(passage(0.46, 0.046)))).isEqualTo(Decision.NONE);
        assertThat(gate.decide(List.of(passage(0.46, 1.0)))).isEqualTo(Decision.NONE);
    }

    @Test
    void decide_whenBelowTheLexicalFloor_isNoneEvenWithARareTerm() {
        assertThat(gate.decide(List.of(passage(0.4499, 0.001)))).isEqualTo(Decision.NONE);
    }

    /**
     * The evidence is read from the most similar passage, not from the first (fused) one or from any passage: a rare
     * term in a passage that is less similar does not open the gate.
     */
    @Test
    void decide_readsTheMostSimilarPassageNotTheFirst() {
        RetrievedPassage firstButWeak = passage(0.30, 0.01);
        RetrievedPassage mostSimilar = passage(0.46, 1.0);
        assertThat(gate.decide(List.of(firstButWeak, mostSimilar))).isEqualTo(Decision.NONE);
        assertThat(gate.decide(List.of(passage(0.30, 1.0), passage(0.46, 0.02)))).isEqualTo(Decision.SPECIFIC_TERM);
        assertThat(gate.decide(List.of(passage(0.30, 1.0), passage(0.47, 1.0)))).isEqualTo(Decision.SIMILARITY);
    }

    @Test
    void decide_whenTheLexicalBranchIsOff_usesTheSimilarityAlone() {
        AnswerabilityGate off = new AnswerabilityGate(properties(0.47, 0.45, 0));
        assertThat(off.decide(List.of(passage(0.46, 0.01)))).isEqualTo(Decision.NONE);
        assertThat(off.decide(List.of(passage(0.47, 1.0)))).isEqualTo(Decision.SIMILARITY);
    }

    /**
     * The prompt keeps the fused order and drops passages under passage-min-similarity (0.50), except the most similar
     * one: a question let through by the specific-term branch (best 0.46) still has its evidence in the prompt.
     */
    @Test
    void promptPassages_keepsTheMostSimilarAndThoseAboveTheFloorInOrder() {
        RetrievedPassage lexicalFirst = passage(0.30, 0.01);
        RetrievedPassage strong = passage(0.62, 1.0);
        RetrievedPassage weak = passage(0.49, 1.0);
        RetrievedPassage edge = passage(0.50, 1.0);
        assertThat(gate.promptPassages(List.of(lexicalFirst, strong, weak, edge))).containsExactly(strong, edge);

        RetrievedPassage evidence = passage(0.46, 0.02);
        assertThat(gate.promptPassages(List.of(lexicalFirst, evidence, passage(0.41, 1.0)))).containsExactly(evidence);
    }

    /** A floor above the threshold, or a share the retrieval does not count exactly, is a configuration error. */
    @Test
    void properties_whenTheGateIsInconsistent_failAtStartup() {
        assertThatThrownBy(() -> properties(0.47, 0.48, 0.045)).hasMessageContaining("lexical-min-similarity");
        assertThatThrownBy(() -> properties(0.47, -0.1, 0.045)).hasMessageContaining("lexical-min-similarity");
        assertThatThrownBy(() -> properties(0.47, 0.45, 0.10)).hasMessageContaining("specific-term-max-share");
        assertThatThrownBy(() -> properties(0.47, 0.45, -0.01)).hasMessageContaining("specific-term-max-share");
        assertThatThrownBy(() -> new QaProperties(5, 0.47, 0.44, 0.045, 1.5, 1500, 1, Duration.ofSeconds(5), 2,
                Duration.ofSeconds(15), Duration.ofSeconds(90), Duration.ofSeconds(30))).hasMessageContaining("passage-min-similarity");
    }

    private static RetrievedPassage passage(double similarity, double share) {
        return new RetrievedPassage(UUID.randomUUID(), "a.pdf", 1, SourceUnit.PAGE, "text", similarity, share);
    }

    private static QaProperties properties(double min, double lexicalMin, double share) {
        return new QaProperties(5, min, lexicalMin, share, 0.50, 1500, 1, Duration.ofSeconds(5), 2, Duration.ofSeconds(15),
                Duration.ofSeconds(90), Duration.ofSeconds(30));
    }
}
