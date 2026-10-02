package com.verso.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stands in for the Ollama bge-m3 model in application tests (no model download in CI): every text gets a
 * deterministic unit vector of 1024 dimensions derived from its content, so equal texts embed equally. Tests can make
 * it fail, answer with another dimension, or count calls. One instance per JVM, reset by each test that changes it.
 */
public final class TestEmbeddingModel implements EmbeddingModel {

    public static final TestEmbeddingModel INSTANCE = new TestEmbeddingModel();
    public static final int DIMENSIONS = 1024;

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private volatile int dimensions = DIMENSIONS;
    private volatile List<String> lastInputs = List.of();
    private volatile boolean nan;
    private volatile Runnable onCall = () -> { };

    private TestEmbeddingModel() {}

    /** Registered as the primary EmbeddingModel; the Ollama one stays unused. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        EmbeddingModel testEmbeddingModel() {
            return INSTANCE;
        }
    }

    public void reset() {
        calls.set(0);
        failure.set(null);
        dimensions = DIMENSIONS;
        lastInputs = List.of();
        nan = false;
        onCall = () -> { };
    }

    /** Vectors containing NaN: not a model failure, the store rejects them (a non-model error path). */
    public void answerWithNaN() {
        nan = true;
    }

    /** Runs before every call, e.g. to start a shutdown while a document is being embedded. */
    public void onCall(Runnable action) {
        onCall = action;
    }

    public void failWith(RuntimeException exception) {
        failure.set(exception);
    }

    public void answerWithDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int calls() {
        return calls.get();
    }

    public List<String> lastInputs() {
        return lastInputs;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        calls.incrementAndGet();
        lastInputs = List.copyOf(request.getInstructions());
        onCall.run();
        RuntimeException error = failure.get();
        if (error != null) throw error;
        List<Embedding> results = new ArrayList<>();
        for (int i = 0; i < request.getInstructions().size(); i++) {
            results.add(new Embedding(vector(request.getInstructions().get(i)), i));
        }
        return new EmbeddingResponse(results);
    }

    @Override
    public float[] embed(Document document) {
        return vector(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] vector(String text) {
        SplittableRandom random = new SplittableRandom(seed(text));
        float[] vector = new float[dimensions];
        double norm = 0;
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) random.nextGaussian();
            norm += vector[i] * vector[i];
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) vector[i] *= scale;
        if (nan) vector[0] = Float.NaN;
        return vector;
    }

    private static long seed(String text) {
        long hash = 1125899906842597L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) hash = 31 * hash + b;
        return hash;
    }
}
