package com.verso.qa.service;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Answer time histogram of the usage rollups (ADR-0023). Fixed upper bounds, so days add up and any range has a p50 and
 * a p95 (the upper bound of the bucket the percentile falls in; an approximation, like Prometheus histograms). The last
 * bound is above the slowest possible answer (10 s embedding + 5 s slot wait + 90 s local chat, ADR-0008).
 */
public final class LatencyBuckets {

    /** Upper bounds in milliseconds; a time above the last one is counted in the last one. */
    static final int[] BOUNDS = {250, 500, 1000, 2000, 3000, 5000, 8000, 13000, 20000, 30000, 45000, 60000, 90000,
            120000, 180000};

    private LatencyBuckets() {}

    public static int bucketFor(long millis) {
        for (int bound : BOUNDS) {
            if (millis <= bound) return bound;
        }
        return BOUNDS[BOUNDS.length - 1];
    }

    /**
     * The bucket bound at quantile {@code q} (0 &lt; q &le; 1) of {@code counts} (bucket bound -&gt; answers), or null
     * without answers.
     */
    public static Long percentile(Map<Integer, Long> counts, double q) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return null;
        long rank = (long) Math.ceil(q * total);
        long seen = 0;
        SortedMap<Integer, Long> ordered = new TreeMap<>(counts);
        for (Map.Entry<Integer, Long> bucket : ordered.entrySet()) {
            seen += bucket.getValue();
            if (seen >= rank) return (long) bucket.getKey();
        }
        return (long) ordered.lastKey();
    }
}
