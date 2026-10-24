package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The answer time histogram of the usage rollups (ADR-0023). */
class LatencyBucketsTest {

    @Test
    void bucketFor_whenTimed_returnsTheSmallestUpperBoundAtOrAbove() {
        assertThat(LatencyBuckets.bucketFor(0)).isEqualTo(250);
        assertThat(LatencyBuckets.bucketFor(250)).isEqualTo(250);
        assertThat(LatencyBuckets.bucketFor(251)).isEqualTo(500);
        assertThat(LatencyBuckets.bucketFor(14_000)).isEqualTo(20_000);
        assertThat(LatencyBuckets.bucketFor(10_000_000)).as("above the last bound").isEqualTo(180_000);
    }

    @Test
    void percentile_whenCounted_isTheBoundWhereTheRankFalls() {
        // 10 answers: 6 under 1 s, 3 under 3 s, 1 under 30 s.
        Map<Integer, Long> counts = Map.of(1000, 6L, 3000, 3L, 30000, 1L);
        assertThat(LatencyBuckets.percentile(counts, 0.50)).isEqualTo(1000L);
        assertThat(LatencyBuckets.percentile(counts, 0.90)).isEqualTo(3000L);
        assertThat(LatencyBuckets.percentile(counts, 0.95)).isEqualTo(30000L);
        assertThat(LatencyBuckets.percentile(Map.of(), 0.5)).isNull();
        assertThat(LatencyBuckets.percentile(Map.of(500, 0L), 0.5)).isNull();
    }
}
