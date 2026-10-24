package com.verso.qa.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Question answering metrics for Prometheus (ADR-0014): questions by outcome, retrieval and chat durations (histograms,
 * for the p95 alert), and the chat model circuit. The only tag is the outcome: no account, question, model output or
 * document (llm-rules 2.1).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QaMetrics {

    /** How a question ended; CANCELLED: a streamed answer the client left before its end (ADR-0035). */
    public enum Outcome {
        ANSWERED, UNCITED, NOT_FOUND, MODEL_UNAVAILABLE, MODEL_BUSY, CIRCUIT_OPEN, RETRIEVAL_FAILED, CANCELLED
    }

    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);
    private final Timer retrieval;
    private final Timer chat;
    private final MeterRegistry registry;
    private final Map<String, Counter> notStored = new java.util.concurrent.ConcurrentHashMap<>();

    public QaMetrics(MeterRegistry registry, ModelCircuitBreaker circuit) {
        this.registry = registry;
        // Registered at 0 up front: a series that first appears at 1 has no increase() for the HistoryNotStored alert.
        turnNotStoredCounter("failed");
        turnNotStoredCounter("conversation_full");
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(outcome, Counter.builder("verso.qa.questions").tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .description("Questions by outcome").register(registry));
        }
        retrieval = Timer.builder("verso.qa.retrieval.duration").description("Question embedding and vector search")
                .publishPercentileHistogram().maximumExpectedValue(Duration.ofSeconds(30)).register(registry);
        chat = Timer.builder("verso.qa.chat.duration").description("One chat model call")
                .publishPercentileHistogram().maximumExpectedValue(Duration.ofSeconds(120)).register(registry);
        Gauge.builder("verso.qa.circuit.open", circuit, c -> c.isOpen() ? 1 : 0)
                .description("1 while the chat model circuit is open").register(registry);
    }

    public void record(Outcome outcome) {
        outcomes.get(outcome).increment();
    }

    /**
     * An answer that could not be stored in the history (ADR-0023): "failed" (database) or "conversation_full". The
     * answer reached the caller anyway; a rising count means history and usage metrics are missing questions.
     */
    public void turnNotStored(String reason) {
        turnNotStoredCounter(reason).increment();
    }

    private Counter turnNotStoredCounter(String reason) {
        return notStored.computeIfAbsent(reason, r -> Counter.builder("verso.qa.history.not.stored").tag("reason", r)
                .description("Answers that could not be stored in the history").register(registry));
    }

    public void retrievalTook(long millis) {
        retrieval.record(Duration.ofMillis(millis));
    }

    public void chatTook(long millis) {
        chat.record(Duration.ofMillis(millis));
    }
}
