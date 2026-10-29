package com.verso.document.service;

import com.verso.document.config.RerankerProperties;
import com.verso.document.service.RerankerClient.RerankException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The reranker on the question's hot path (ADR-0043), guarded like every remote call (AGENTS.md 5): a hard deadline
 * ({@code timeout}, the HTTP call is interrupted after it), a bulkhead ({@code concurrency} calls per instance) and a
 * circuit breaker ({@code circuit-failures} failures in a row skip the sidecar for {@code circuit-open}). It never
 * throws for a sidecar problem: an empty result means "keep the fused order", which is retrieval without a reranker.
 * Logs and metrics carry a fixed outcome only, never the question or a passage (llm-rules 2.1). The breaker's state is
 * per instance, like the docling one (ADR-0032).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PassageReranker {

    /** What happened to one rerank. Fixed values: they reach logs and a metric tag. */
    enum Outcome { RERANKED, CIRCUIT_OPEN, BUSY, UNAVAILABLE, TIMEOUT, INVALID_RESPONSE }

    private static final Logger log = LoggerFactory.getLogger(PassageReranker.class);
    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

    private final RerankerClient client;
    private final RerankerProperties properties;
    private final Clock clock;
    private final Semaphore inFlight;
    private final AtomicInteger failures = new AtomicInteger();
    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);
    private final Timer duration;
    private volatile Instant openUntil = Instant.MIN;

    public PassageReranker(RerankerClient client, RerankerProperties properties, Clock clock, MeterRegistry registry) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.inFlight = new Semaphore(properties.concurrency());
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(outcome, Counter.builder("verso.retrieval.rerank")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .description("Rerank calls by outcome; anything but reranked kept the fused order")
                    .register(registry));
        }
        this.duration = Timer.builder("verso.retrieval.rerank.duration")
                .description("Duration of successful rerank calls").register(registry);
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** Fused candidates sent to the reranker. */
    public int candidates() {
        return properties.candidates();
    }

    /**
     * The order of {@code texts}, most relevant first (a permutation of their indexes), or empty when the reranker is
     * off, skipped or failed: the caller then keeps the fused order.
     */
    public Optional<List<Integer>> order(String question, List<String> texts) {
        if (!properties.enabled() || texts.size() < 2) return Optional.empty();
        return guarded(question, texts, client::rank);
    }

    /**
     * ADR-0060: the relevance of {@code text} to {@code question} (sigmoid, 0..1), under the same deadline, bulkhead and
     * circuit breaker as {@link #order}; empty when the reranker is off, skipped or failed. A caller that uses the
     * score as a check must treat empty as "not confirmed".
     */
    public OptionalDouble score(String question, String text) {
        if (!properties.enabled()) return OptionalDouble.empty();
        Optional<double[]> scores = guarded(question, List.of(text), client::scores);
        return scores.isPresent() ? OptionalDouble.of(scores.get()[0]) : OptionalDouble.empty();
    }

    private <T> Optional<T> guarded(String question, List<String> texts, BiFunction<String, List<String>, T> rerank) {
        if (clock.instant().isBefore(openUntil)) return skipped(Outcome.CIRCUIT_OPEN);
        if (!inFlight.tryAcquire()) return skipped(Outcome.BUSY);
        List<String> cut = texts.stream().map(this::cut).toList();
        // Whoever claims "started" owns the release: the task when it runs, the caller when it cancels a task that
        // never started (the same pattern as the question embedding, DocumentRetrievalServiceImpl).
        AtomicBoolean started = new AtomicBoolean();
        Future<T> call;
        try {
            call = VIRTUAL.submit(() -> {
                if (!started.compareAndSet(false, true)) return null;
                try {
                    return rerank.apply(question, cut);
                } finally {
                    inFlight.release();
                }
            });
        } catch (RuntimeException e) {
            inFlight.release();
            return failed(Outcome.UNAVAILABLE);
        }
        long begin = System.nanoTime();
        try {
            T result = call.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            failures.set(0);
            duration.record(System.nanoTime() - begin, TimeUnit.NANOSECONDS);
            outcomes.get(Outcome.RERANKED).increment();
            return Optional.of(result);
        } catch (TimeoutException e) {
            cancel(call, started);
            return failed(Outcome.TIMEOUT);
        } catch (InterruptedException e) {
            cancel(call, started);
            Thread.currentThread().interrupt();
            return failed(Outcome.UNAVAILABLE);
        } catch (ExecutionException e) {
            return failed(e.getCause() instanceof RerankException own
                    ? Outcome.valueOf(own.failure().name()) : Outcome.INVALID_RESPONSE);
        }
    }

    private String cut(String text) {
        int max = properties.maxPassageChars();
        if (text.length() <= max) return text;
        // Never split a surrogate pair: the JSON encoder would write a lone surrogate.
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    private void cancel(Future<?> call, AtomicBoolean started) {
        call.cancel(true);
        if (started.compareAndSet(false, true)) inFlight.release();
    }

    private <T> Optional<T> skipped(Outcome outcome) {
        outcomes.get(outcome).increment();
        return Optional.empty();
    }

    private <T> Optional<T> failed(Outcome outcome) {
        outcomes.get(outcome).increment();
        int count = failures.incrementAndGet();
        if (count >= properties.circuitFailures()) {
            failures.set(0);
            openUntil = clock.instant().plus(properties.circuitOpen());
            log.warn("Reranker skipped: reason={} consecutiveFailures={} openSeconds={}", outcome, count,
                    properties.circuitOpen().toSeconds());
        }
        return Optional.empty();
    }

    /** True while the breaker skips the sidecar; for tests. */
    boolean open() {
        return clock.instant().isBefore(openUntil);
    }
}
