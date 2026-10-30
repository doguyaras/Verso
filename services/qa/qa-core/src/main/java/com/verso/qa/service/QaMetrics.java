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
        ANSWERED, UNCITED, NOT_FOUND, MODEL_UNAVAILABLE, MODEL_BUSY, CIRCUIT_OPEN, RETRIEVAL_FAILED, CANCELLED,
        /** Answered from a verified answer card (ADR-0049): no model wrote it. */
        MEMORY
    }

    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);
    private final Timer retrieval;
    private final Timer chat;
    private final Timer rewrite;
    private final Timer decompose;
    private final Map<String, Counter> reasoning = new java.util.concurrent.ConcurrentHashMap<>();
    private final MeterRegistry registry;
    private final Map<String, Counter> followUps = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Counter> notStored = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Counter> grounding = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Counter> relevance = new java.util.concurrent.ConcurrentHashMap<>();
    /** Where a stalled answer call was given up: before its first token (streams) or at the chat timeout. */
    public static final String STALL_FIRST_TOKEN = "first_token";
    public static final String STALL_TIMEOUT = "timeout";
    private static final java.util.List<String> STALL_PHASES = java.util.List.of(STALL_FIRST_TOKEN, STALL_TIMEOUT);
    private final Map<String, Counter> stalls = new java.util.HashMap<>();

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
        rewrite = Timer.builder("verso.qa.followup.duration").description("One follow-up rewrite call (ADR-0046)")
                .publishPercentileHistogram().maximumExpectedValue(Duration.ofSeconds(30)).register(registry);
        decompose = Timer.builder("verso.qa.reasoning.decompose.duration")
                .description("One multi-step decomposition call (ADR-0055)")
                .publishPercentileHistogram().maximumExpectedValue(Duration.ofSeconds(30)).register(registry);
        Gauge.builder("verso.qa.circuit.open", circuit, c -> c.isOpen() ? 1 : 0)
                .description("1 while the chat model circuit is open").register(registry);
        // Registered at 0 up front, like the history counter: the ChatStalled alert reads increase().
        for (String phase : STALL_PHASES) stalls.put(phase, Counter.builder("verso.qa.chat.stalls").tag("phase", phase)
                .description("Answer chat calls given up at a time bound (ADR-0062)").register(registry));
    }

    /**
     * An answer chat call cut at a time bound (ADR-0062): "first_token" (a stream silent past the first-token timeout)
     * or "timeout" (the chat timeout). A rising count with no answers means a stuck model runner, not a slow one.
     */
    public void chatStalled(String phase) {
        stalls.get(phase).increment();
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

    /**
     * What happened to a question asked in a conversation (ADR-0046): "standalone" (the heuristic skipped the rewrite),
     * "no_history", "rewritten", "unchanged" (the model repeated the question), "fallback" (timeout, failure or an
     * output that is not a query: searched as asked). No content, a fixed set of tags.
     */
    public void followUp(String result) {
        followUps.computeIfAbsent(result, r -> Counter.builder("verso.qa.followup").tag("result", r)
                .description("Questions asked in a conversation, by follow-up handling").register(registry)).increment();
    }

    /**
     * The groundedness check of one answer (ADR-0047), counted by sentence: "verified" and "unverified" sentences
     * stated an anchor, "unchecked" ones cited a passage but stated none. A rising unverified share means the model
     * restates numbers its sources do not give. No content, a fixed set of tags.
     */
    public void grounding(int cited, int checked, int unverified) {
        groundingCounter("verified").increment(checked - unverified);
        groundingCounter("unverified").increment(unverified);
        groundingCounter("unchecked").increment(cited - checked);
    }

    private Counter groundingCounter(String result) {
        return grounding.computeIfAbsent(result, r -> Counter.builder("verso.qa.grounding.sentences").tag("result", r)
                .description("Cited answer sentences by groundedness check result").register(registry));
    }

    /**
     * ADR-0064: a model answer the relevance check turned into "not found", by verdict ("refusal",
     * "refusal_with_filler", "slot_missing") and question type ("person", "date", "place", "number", "entity",
     * "other"). A rising slot_missing share means the model answers questions the documents do not answer. Fixed
     * words, no content.
     */
    public void relevance(String verdict, String questionType) {
        relevance.computeIfAbsent(verdict + "|" + questionType, k -> Counter.builder("verso.qa.relevance.abstained")
                .tag("verdict", verdict).tag("type", questionType)
                .description("Model answers turned into not found by the relevance check (ADR-0064)")
                .register(registry)).increment();
    }

    /** One rewrite call, whatever its result (it holds the chat slot for that long). */
    public void rewriteTook(long millis) {
        rewrite.record(Duration.ofMillis(millis));
    }

    public void retrievalTook(long millis) {
        retrieval.record(Duration.ofMillis(millis));
    }

    public void chatTook(long millis) {
        chat.record(Duration.ofMillis(millis));
    }

    /**
     * ADR-0055: what multi-step reasoning did with a question while it is on ("not_triggered", "decomposed",
     * "fallback_invalid", "fallback_timeout", "fallback_busy", "fallback_failed", "fallback_retrieval",
     * "fallback_no_passages"). A fixed set of words, no content.
     */
    public void reasoning(String result) {
        reasoning.computeIfAbsent(result, r -> Counter.builder("verso.qa.reasoning").tag("result", r)
                .description("Questions by multi-step reasoning handling (ADR-0055)").register(registry)).increment();
    }

    /** One decomposition call, whatever its result (it holds the chat slot for that long). */
    public void decomposeTook(long millis) {
        decompose.record(Duration.ofMillis(millis));
    }
}
