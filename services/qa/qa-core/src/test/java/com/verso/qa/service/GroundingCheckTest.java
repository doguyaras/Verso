package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.UnverifiedSentence;
import com.verso.qa.config.QaGroundingProperties;
import com.verso.qa.config.QaProperties;
import com.verso.qa.service.GroundingCheck.Grounding;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GroundingCheckTest {

    private static final QaProperties PROPERTIES = properties(1500);
    private static final GroundingCheck CHECK = new GroundingCheck(new QaGroundingProperties(true), PROPERTIES);
    private static final List<RetrievedPassage> PASSAGES = List.of(
            passage("Doğum yapan çalışana toplam on altı hafta ücretli doğum izni verilir."),
            passage("Kişisel araçla yapılan yolculuklarda kilometre başına 9 TL ödenir."));

    @Test
    void anUnsupportedSentenceIsListedWithItsOffsetsAndCitations() {
        String answer = "Doğum izni on altı haftadır [1]. Kilometre başına 12 TL ödenir [2].";
        Grounding grounding = CHECK.check(answer, PASSAGES, "Ne kadar?");
        assertThat(grounding.cited()).isEqualTo(2);
        assertThat(grounding.checked()).isEqualTo(2);
        assertThat(grounding.unverified()).hasSize(1);
        UnverifiedSentence sentence = grounding.unverified().getFirst();
        assertThat(answer.substring(sentence.start(), sentence.end())).isEqualTo("Kilometre başına 12 TL ödenir [2].");
        assertThat(sentence.citations()).containsExactly(2);
        assertThat(grounding.noneVerified()).isFalse();
    }

    @Test
    void aSentenceIsCheckedOnlyAgainstThePassagesItCites() {
        // 9 TL is in passage 2, but the sentence cites passage 1.
        Grounding grounding = CHECK.check("Kilometre başına 9 TL ödenir [1].", PASSAGES, "");
        assertThat(grounding.unverified()).hasSize(1);
        assertThat(grounding.noneVerified()).isTrue();
    }

    @Test
    void aMarkerOpeningTheNextSentenceBelongsToTheSentenceBefore() {
        String answer = "Kilometre başına 12 TL ödenir. [2] Ayrıca otopark ödenmez.";
        Grounding grounding = CHECK.check(answer, PASSAGES, "");
        assertThat(grounding.cited()).isEqualTo(1);
        assertThat(answer.substring(grounding.unverified().getFirst().start(), grounding.unverified().getFirst().end()))
                .isEqualTo("Kilometre başına 12 TL ödenir.");
    }

    @Test
    void sentencesWithoutAnchorsOrMarkersAreNotChecked() {
        Grounding grounding = CHECK.check("Doğum izni ücretlidir [1]. Kilometre başına 12 TL ödenir.", PASSAGES, "");
        assertThat(grounding.cited()).isEqualTo(1);
        assertThat(grounding.checked()).isZero();
        assertThat(grounding.unverified()).isEmpty();
        assertThat(grounding.noneVerified()).isFalse();
    }

    @Test
    void anUncheckableCitedSentenceKeepsTheAnswerSourced() {
        Grounding grounding = CHECK.check("Doğum izni ücretlidir [1]. Kilometre başına 12 TL ödenir [2].", PASSAGES, "");
        assertThat(grounding.unverified()).hasSize(1);
        assertThat(grounding.noneVerified()).isFalse();
    }

    @Test
    void onlyThePromptPartOfAPassageIsEvidence() {
        QaProperties shortPassages = properties(100);
        GroundingCheck check = new GroundingCheck(new QaGroundingProperties(true), shortPassages);
        List<RetrievedPassage> passages = List.of(passage("Doğum izni ".repeat(10) + "toplam on altı haftadır."));
        // "on altı hafta" lies after the first 100 characters the prompt carried.
        assertThat(check.check("Doğum izni on altı haftadır [1].", passages, "").unverified()).hasSize(1);
        assertThat(CHECK.check("Doğum izni on altı haftadır [1].", passages, "").unverified()).isEmpty();
    }

    @Test
    void offMeansNoResult() {
        GroundingCheck off = new GroundingCheck(new QaGroundingProperties(false), PROPERTIES);
        Grounding grounding = off.check("Kilometre başına 12 TL ödenir [2].", PASSAGES, "");
        assertThat(grounding.unverified()).isNull();
        assertThat(grounding.noneVerified()).isFalse();
    }

    @Test
    void metrics_whenAnAnswerIsChecked_countSentencesByResultWithoutContent() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        QaMetrics metrics = new QaMetrics(registry, new ModelCircuitBreaker(PROPERTIES));
        Grounding grounding = CHECK.check("Doğum izni on altı haftadır [1]. Kilometre başına 12 TL ödenir [2]. "
                + "Doğum izni ücretlidir [1].", PASSAGES, "");
        metrics.grounding(grounding.cited(), grounding.checked(), grounding.unverified().size());
        assertThat(registry.get("verso.qa.grounding.sentences").tag("result", "verified").counter().count()).isEqualTo(1);
        assertThat(registry.get("verso.qa.grounding.sentences").tag("result", "unverified").counter().count()).isEqualTo(1);
        assertThat(registry.get("verso.qa.grounding.sentences").tag("result", "unchecked").counter().count()).isEqualTo(1);
        assertThat(registry.find("verso.qa.grounding.sentences").meters()).hasSize(3);
    }

    @Test
    void anEmptyAnswerHasNothingToCheck() {
        assertThat(CHECK.check("", PASSAGES, "").unverified()).isEmpty();
    }

    private static RetrievedPassage passage(String content) {
        return new RetrievedPassage(UUID.randomUUID(), "belge.pdf", 1, SourceUnit.PAGE, content, 0.8);
    }

    private static QaProperties properties(int maxPassageChars) {
        return new QaProperties(5, 0.5, 0.5, 0.045, 0.5, maxPassageChars, 1, Duration.ofSeconds(5), 2,
                Duration.ofSeconds(15), Duration.ofSeconds(90), Duration.ofSeconds(30));
    }
}
