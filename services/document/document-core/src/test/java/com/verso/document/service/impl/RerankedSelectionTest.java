package com.verso.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0043: how the reranked top-k is built from the fused candidates. It follows the reranker's order, and the
 * answerability gate (ADR-0022: cosine of the most similar returned passage) reads exactly the passage it would read
 * without the reranker: the most similar of the fused top-k is always returned and stays the most similar one.
 */
class RerankedSelectionTest {

    private static RetrievedPassage passage(String name, double similarity) {
        return new RetrievedPassage(UUID.randomUUID(), name, 1, SourceUnit.PAGE, name, similarity, 1.0, null, null,
                UUID.randomUUID());
    }

    private static List<String> names(List<RetrievedPassage> passages) {
        return passages.stream().map(RetrievedPassage::fileName).toList();
    }

    @Test
    void reranked_followsTheRerankersOrderAndKeepsTopK() {
        // Fused order a..f; the evidence (most similar of the top 3) is b.
        List<RetrievedPassage> fused = List.of(passage("a", 0.60), passage("b", 0.70), passage("c", 0.55),
                passage("d", 0.50), passage("e", 0.40), passage("f", 0.30));

        List<RetrievedPassage> top = DocumentRetrievalServiceImpl.reranked(fused, List.of(4, 3, 1, 0, 2, 5), 3);

        assertThat(names(top)).containsExactly("e", "d", "b");
    }

    @Test
    void reranked_whenTheRerankerPutsTheEvidenceLow_returnsItInPlaceOfTheLast() {
        List<RetrievedPassage> fused = List.of(passage("a", 0.70), passage("b", 0.60), passage("c", 0.55),
                passage("d", 0.50), passage("e", 0.40));

        List<RetrievedPassage> top = DocumentRetrievalServiceImpl.reranked(fused, List.of(4, 3, 2, 1, 0), 3);

        assertThat(names(top)).containsExactly("e", "d", "a");
    }

    @Test
    void reranked_leavesOutAPassageMoreSimilarThanTheEvidence() {
        // d is beyond the fused top 3 and more similar than the evidence a: it would raise what the gate reads.
        List<RetrievedPassage> fused = List.of(passage("a", 0.46), passage("b", 0.45), passage("c", 0.40),
                passage("d", 0.52), passage("e", 0.30));

        List<RetrievedPassage> top = DocumentRetrievalServiceImpl.reranked(fused, List.of(3, 4, 0, 1, 2), 3);

        assertThat(names(top)).containsExactly("e", "a", "b");
    }

    @Test
    void reranked_withFewerCandidatesThanTopK_returnsThemAll() {
        List<RetrievedPassage> fused = List.of(passage("a", 0.60), passage("b", 0.50));

        assertThat(names(DocumentRetrievalServiceImpl.reranked(fused, List.of(1, 0), 5))).containsExactly("b", "a");
    }

    /** The gate's input is unchanged for any order: same most similar passage, so the same decision. */
    @Test
    void reranked_forAnyOrder_theMostSimilarPassageIsTheFusedTopKsOne() {
        Random random = new Random(43);
        for (int round = 0; round < 2000; round++) {
            int n = 2 + random.nextInt(39);
            int topK = 1 + random.nextInt(8);
            List<RetrievedPassage> fused = new ArrayList<>();
            for (int i = 0; i < n; i++) fused.add(passage("p" + i, Math.round(random.nextDouble() * 100) / 100.0));
            List<Integer> order = new ArrayList<>(IntStream.range(0, n).boxed().toList());
            Collections.shuffle(order, random);

            List<RetrievedPassage> top = DocumentRetrievalServiceImpl.reranked(fused, order, topK);

            RetrievedPassage evidence = fused.subList(0, Math.min(topK, n)).stream()
                    .max(Comparator.comparingDouble(RetrievedPassage::similarity)).orElseThrow();
            RetrievedPassage gateReads = top.stream().max(Comparator.comparingDouble(RetrievedPassage::similarity)).orElseThrow();
            assertThat(gateReads).isSameAs(evidence);
            assertThat(top).hasSize(Math.min(topK, (int) fused.stream()
                    .filter(p -> p == evidence || p.similarity() < evidence.similarity()).count()));
            assertThat(top).doesNotHaveDuplicates();
        }
    }
}
