package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.qa.service.QuestionClusterer.Cluster;
import com.verso.qa.service.QuestionClusterer.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-0052: the clustering of uncertain questions is deterministic and separates topics by cosine similarity. */
class QuestionClustererTest {

    private static final int DIMENSIONS = 64;

    @Test
    void cluster_whenTheSameQuestionsComeInAnyOrder_givesTheSameClusters() {
        List<Point> points = topics(3, 12, 1);
        List<Cluster> first = QuestionClusterer.cluster(points, 0.80);
        for (long seed = 2; seed < 7; seed++) {
            List<Point> shuffled = new ArrayList<>(points);
            Collections.shuffle(shuffled, new Random(seed));
            List<Cluster> again = QuestionClusterer.cluster(shuffled, 0.80);
            assertThat(again).hasSameSizeAs(first);
            for (int i = 0; i < first.size(); i++) {
                assertThat(again.get(i).turnIds()).isEqualTo(first.get(i).turnIds());
                assertThat(again.get(i).centroid()).containsExactly(first.get(i).centroid());
            }
        }
    }

    @Test
    void cluster_whenThreeTopicsAreAsked_findsThreeClustersLargestFirst() {
        List<Point> points = new ArrayList<>(topics(2, 10, 11));
        points.addAll(topic(2, 4, 99));
        List<Cluster> clusters = QuestionClusterer.cluster(points, 0.80);
        assertThat(clusters).extracting(Cluster::size).containsExactly(10, 10, 4);
        // Every member of a cluster belongs to one topic (the topic is in the id's most significant bits).
        for (Cluster cluster : clusters) {
            assertThat(cluster.turnIds().stream().map(UUID::getMostSignificantBits).distinct()).hasSize(1);
        }
    }

    @Test
    void cluster_whenTheThresholdIsAboveEveryPair_keepsEveryQuestionAlone() {
        assertThat(QuestionClusterer.cluster(topics(2, 5, 3), 0.9999)).hasSize(10);
        assertThat(QuestionClusterer.cluster(List.of(), 0.8)).isEmpty();
        assertThatThrownBy(() -> QuestionClusterer.cluster(List.of(), 1.0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cosine_whenComparedWithItselfOrAnOrthogonalVector_isOneOrZero() {
        float[] a = {1, 0, 0};
        assertThat(QuestionClusterer.cosine(a, a)).isEqualTo(1.0);
        assertThat(QuestionClusterer.cosine(a, new float[] {0, 1, 0})).isZero();
        assertThat(QuestionClusterer.cosine(a, new float[3])).isZero();
    }

    /** {@code count} topics of {@code size} questions each: a random base vector per topic plus small noise. */
    private static List<Point> topics(int count, int size, long seed) {
        List<Point> points = new ArrayList<>();
        for (int t = 0; t < count; t++) points.addAll(topic(t, size, seed * 31 + t));
        return points;
    }

    private static List<Point> topic(int topic, int size, long seed) {
        Random random = new Random(seed);
        float[] base = new float[DIMENSIONS];
        for (int d = 0; d < DIMENSIONS; d++) base[d] = (float) random.nextGaussian();
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            float[] vector = new float[DIMENSIONS];
            for (int d = 0; d < DIMENSIONS; d++) vector[d] = base[d] + (float) (random.nextGaussian() * 0.15);
            points.add(new Point(new UUID(topic + seed * 1000, i), vector));
        }
        return points;
    }
}
