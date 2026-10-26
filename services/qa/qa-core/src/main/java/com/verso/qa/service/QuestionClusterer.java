package com.verso.qa.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Clusters question vectors by cosine similarity (ADR-0052): a deterministic leader pass (each question joins the most
 * similar cluster whose centroid is at least {@code threshold} similar, else starts one) followed by a few k-means style
 * refinement passes against the centroids. No model call, no randomness: the input is ordered by turn id first, so the
 * same questions always give the same clusters whatever order they were read in. O(questions x clusters) per pass.
 *
 * <p>A cluster here is only a candidate: the caller keeps those with at least k distinct askers (k-anonymity).
 */
public final class QuestionClusterer {

    /** Refinement passes after the leader pass; they usually settle in two. */
    static final int REFINEMENTS = 5;

    /** One question: its turn and its vector (any length, normalised here). */
    public record Point(UUID turnId, float[] vector) {

        @Override
        public String toString() {
            return "Point[turnId=" + turnId + "]";
        }
    }

    /**
     * A cluster: its members (turn ids, ascending) and its centroid (unit length).
     */
    public record Cluster(List<UUID> turnIds, float[] centroid) {

        public Cluster {
            turnIds = List.copyOf(turnIds);
        }

        public int size() {
            return turnIds.size();
        }

        @Override
        public String toString() {
            return "Cluster[size=" + turnIds.size() + "]";
        }
    }

    private QuestionClusterer() {
    }

    /** The clusters of {@code points}, largest first (ties: the smallest first turn id). */
    public static List<Cluster> cluster(List<Point> points, double threshold) {
        if (threshold <= 0 || threshold >= 1) throw new IllegalArgumentException("threshold must be in (0, 1)");
        List<Point> ordered = new ArrayList<>(points);
        ordered.sort(Comparator.comparing(Point::turnId));
        int n = ordered.size();
        if (n == 0) return List.of();
        float[][] vectors = new float[n][];
        for (int i = 0; i < n; i++) vectors[i] = unit(ordered.get(i).vector());
        int[] assignment = new int[n];
        List<double[]> sums = new ArrayList<>();
        List<float[]> centroids = new ArrayList<>();
        // Leader pass.
        for (int i = 0; i < n; i++) {
            int best = nearest(vectors[i], centroids, threshold);
            if (best < 0) {
                best = centroids.size();
                sums.add(new double[vectors[i].length]);
                centroids.add(vectors[i]);
            }
            add(sums.get(best), vectors[i]);
            centroids.set(best, unit(sums.get(best)));
            assignment[i] = best;
        }
        // Refinement: reassign against the current centroids; a question no centroid reaches keeps its cluster.
        for (int pass = 0; pass < REFINEMENTS; pass++) {
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int best = nearest(vectors[i], centroids, threshold);
                if (best >= 0 && best != assignment[i]) {
                    assignment[i] = best;
                    changed = true;
                }
            }
            centroids = recompute(vectors, assignment, centroids.size());
            if (!changed) break;
        }
        List<List<UUID>> members = new ArrayList<>();
        for (int c = 0; c < centroids.size(); c++) members.add(new ArrayList<>());
        for (int i = 0; i < n; i++) members.get(assignment[i]).add(ordered.get(i).turnId());
        List<Cluster> clusters = new ArrayList<>();
        for (int c = 0; c < centroids.size(); c++) {
            if (!members.get(c).isEmpty()) clusters.add(new Cluster(members.get(c), centroids.get(c)));
        }
        clusters.sort(Comparator.comparingInt(Cluster::size).reversed()
                .thenComparing(cluster -> cluster.turnIds().getFirst()));
        return List.copyOf(clusters);
    }

    /** Cosine similarity of two vectors of the same length (0 for a zero vector). */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("vectors of different length");
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    /** The index of the most similar centroid at or above the threshold (ties: the lowest index), or -1. */
    private static int nearest(float[] vector, List<float[]> centroids, double threshold) {
        int best = -1;
        double bestSimilarity = Double.NEGATIVE_INFINITY;
        for (int c = 0; c < centroids.size(); c++) {
            double similarity = cosine(vector, centroids.get(c));
            if (similarity >= threshold && similarity > bestSimilarity) {
                best = c;
                bestSimilarity = similarity;
            }
        }
        return best;
    }

    private static List<float[]> recompute(float[][] vectors, int[] assignment, int count) {
        double[][] sums = new double[count][];
        for (int i = 0; i < vectors.length; i++) {
            if (sums[assignment[i]] == null) sums[assignment[i]] = new double[vectors[i].length];
            add(sums[assignment[i]], vectors[i]);
        }
        List<float[]> centroids = new ArrayList<>(count);
        for (int c = 0; c < count; c++) {
            // An emptied cluster keeps a zero centroid: nothing is similar to it, and it is dropped at the end.
            centroids.add(sums[c] == null ? new float[vectors[0].length] : unit(sums[c]));
        }
        return centroids;
    }

    private static void add(double[] sum, float[] vector) {
        for (int i = 0; i < vector.length; i++) sum[i] += vector[i];
    }

    private static float[] unit(float[] vector) {
        double norm = 0;
        for (float v : vector) norm += (double) v * v;
        norm = Math.sqrt(norm);
        float[] out = new float[vector.length];
        if (norm == 0) return out;
        for (int i = 0; i < vector.length; i++) out[i] = (float) (vector[i] / norm);
        return out;
    }

    private static float[] unit(double[] vector) {
        double norm = 0;
        for (double v : vector) norm += v * v;
        norm = Math.sqrt(norm);
        float[] out = new float[vector.length];
        if (norm == 0) return out;
        for (int i = 0; i < vector.length; i++) out[i] = (float) (vector[i] / norm);
        return out;
    }
}
