package com.verso.document.worker;

import com.verso.document.repository.IngestionRepository;
import com.verso.document.repository.IngestionRepository.QueueStats;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Ingestion metrics for Prometheus (ADR-0014): the queue, the worker's pause, and finished documents by outcome. No
 * account, document id or file name is a tag (llm-rules 2.1, low cardinality). The queue is read at scrape time, at
 * most once per 5 s; a database outage reads as NaN, not as an empty queue.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IngestionMetrics {

    /** What happened to a claimed document. */
    public enum Outcome { READY, FAILED, RETRY, RELEASED, LOST }

    private static final Duration CACHE = Duration.ofSeconds(5);
    private static final QueueStats UNKNOWN = new QueueStats(-1, -1, Double.NaN);

    private final IngestionRepository repository;
    private final Clock clock;
    private final MeterRegistry registry;
    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);
    private volatile QueueStats stats = UNKNOWN;
    private volatile Instant readAt = Instant.MIN;

    public IngestionMetrics(IngestionRepository repository, MeterRegistry registry, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        this.registry = registry;
        gauge(registry, "verso.ingestion.queue", "pending", s -> s.pending() < 0 ? Double.NaN : s.pending(),
                "Documents waiting for the worker");
        gauge(registry, "verso.ingestion.queue", "processing", s -> s.processing() < 0 ? Double.NaN : s.processing(),
                "Documents being processed by a worker");
        Gauge.builder("verso.ingestion.oldest.due.wait", this, m -> m.current().oldestDueWaitSeconds())
                .baseUnit("seconds").description("How long the oldest due document has been waiting")
                .register(registry);
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(outcome, Counter.builder("verso.ingestion.documents")
                    .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                    .description("Claimed documents by outcome").register(registry));
        }
    }

    /** 1 while the embedding model circuit is open and the worker claims nothing (alert ModelUnavailable). */
    void registerPause(java.util.function.BooleanSupplier paused) {
        // Strong reference: Micrometer holds gauge state weakly, and nothing else holds this lambda; without it the
        // gauge read NaN from the first garbage collection on (phase 7 review SP1).
        Gauge.builder("verso.ingestion.paused", paused, p -> p.getAsBoolean() ? 1 : 0).strongReference(true)
                .description("1 while ingestion is paused because the embedding model failed").register(registry);
    }

    public void record(Outcome outcome) {
        outcomes.get(outcome).increment();
    }

    private void gauge(MeterRegistry registry, String name, String status, ToDoubleFunction<QueueStats> value,
                       String description) {
        Gauge.builder(name, this, m -> value.applyAsDouble(m.current())).tag("status", status)
                .description(description).register(registry);
    }

    private QueueStats current() {
        Instant now = clock.instant();
        if (now.isAfter(readAt.plus(CACHE))) {
            try {
                stats = repository.queueStats();
            } catch (RuntimeException e) {
                stats = UNKNOWN;
            }
            readAt = now;
        }
        return stats;
    }
}
