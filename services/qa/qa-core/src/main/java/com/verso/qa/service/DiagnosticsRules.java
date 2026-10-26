package com.verso.qa.service;

import com.verso.qa.api.dto.ClusterTrend;
import com.verso.qa.config.QaDiagnosticsProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * The pure rules of the diagnostics (ADR-0052): the k-anonymity gate, the trend of a cluster and the matching of a
 * run's clusters with the previous run's. No I/O, unit-tested.
 */
public final class DiagnosticsRules {

    /** GROWING at this ratio or more against the previous size, SHRINKING at the inverse of 1.25 (0.8) or less. */
    static final double GROWTH = 1.25;

    private DiagnosticsRules() {
    }

    /**
     * Whether an aggregate of {@code askers} distinct accounts may be shown: at least {@code k}, and never below the
     * floor of 5 whatever {@code k} says (ADR-0048).
     */
    public static boolean kAnonymous(long askers, int k) {
        return askers >= Math.max(k, QaDiagnosticsProperties.MIN_ASKERS_FLOOR);
    }

    /** The trend of a cluster of {@code turns} questions whose matching previous cluster had {@code previous}. */
    public static ClusterTrend trend(int turns, Integer previous) {
        if (previous == null || previous < 1) return ClusterTrend.NEW;
        if (turns >= previous * GROWTH) return ClusterTrend.GROWING;
        if (turns * GROWTH <= previous) return ClusterTrend.SHRINKING;
        return ClusterTrend.STABLE;
    }

    /**
     * For each current centroid, in order (largest cluster first), the index of the most similar previous centroid at
     * or above {@code threshold} that no earlier cluster took, or -1. Deterministic: ties go to the lower index.
     */
    public static List<Integer> matchPrevious(List<float[]> current, List<float[]> previous, double threshold) {
        boolean[] taken = new boolean[previous.size()];
        List<Integer> matches = new ArrayList<>(current.size());
        for (float[] centroid : current) {
            int best = -1;
            double bestSimilarity = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < previous.size(); p++) {
                if (taken[p] || previous.get(p).length != centroid.length) continue;
                double similarity = QuestionClusterer.cosine(centroid, previous.get(p));
                if (similarity >= threshold && similarity > bestSimilarity) {
                    best = p;
                    bestSimilarity = similarity;
                }
            }
            if (best >= 0) taken[best] = true;
            matches.add(best);
        }
        return matches;
    }
}
