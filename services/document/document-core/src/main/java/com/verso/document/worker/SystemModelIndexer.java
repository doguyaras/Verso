package com.verso.document.worker;

import com.verso.document.repository.SystemModelRepository;
import com.verso.document.repository.SystemModelRepository.ChunkRow;
import com.verso.document.systemmodel.ExtractedModel;
import com.verso.document.systemmodel.SystemModelExtractor;
import com.verso.document.systemmodel.SystemModelPlan;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Extracts one document's system model (ADR-0054) when its chunks or the extractor changed, and replaces the stored
 * one; otherwise does nothing (idempotent: a retried run writes nothing). Runs in the caller's transaction: the
 * ingestion worker right after a document became READY, the backfill job under its row lock. No model call; the cost
 * is measured per document ({@code verso.document.system_model.extraction}). Logs carry ids and counts only.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SystemModelIndexer {

    private static final Logger log = LoggerFactory.getLogger(SystemModelIndexer.class);

    /** What a run did. */
    public enum Outcome { EXTRACTED, CURRENT, GONE }

    private final SystemModelRepository repository;
    private final MeterRegistry meters;
    private final Timer timer;

    public SystemModelIndexer(SystemModelRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.meters = meters;
        this.timer = Timer.builder("verso.document.system_model.extraction")
                .description("Deterministic system model extraction per document (ADR-0054)").register(meters);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome index(UUID documentId, Instant now) {
        long started = System.nanoTime();
        // Serialise with any concurrent extraction of this document, then decide on what that one committed. A document
        // deleted meanwhile has nothing to extract and no row for the state: not an error, not a model.
        if (!repository.lockDocument(documentId)) {
            meters.counter("verso.document.system_model.runs", "outcome", "gone").increment();
            return Outcome.GONE;
        }
        List<ChunkRow> chunks = repository.chunks(documentId);
        String digest = SystemModelPlan.digest(chunks.stream().map(ChunkRow::sha256).toList());
        if (!SystemModelPlan.needsExtraction(repository.state(documentId).orElse(null), SystemModelExtractor.VERSION,
                digest)) {
            meters.counter("verso.document.system_model.runs", "outcome", "current").increment();
            return Outcome.CURRENT;
        }
        ExtractedModel model = SystemModelExtractor.extract(chunks.stream()
                .map(c -> new SystemModelExtractor.ChunkText(c.chunkIndex(), c.content())).toList());
        repository.replace(documentId, model, chunks, digest, SystemModelExtractor.VERSION, now);
        long nanos = System.nanoTime() - started;
        timer.record(java.time.Duration.ofNanos(nanos));
        meters.counter("verso.document.system_model.runs", "outcome", "extracted").increment();
        log.info("System model extracted: documentId={} chunks={} entities={} relations={} mentions={} truncated={} "
                        + "durationMs={}", documentId, chunks.size(), model.entities().size(), model.relations().size(),
                model.mentions().size(), model.truncated(), nanos / 1_000_000);
        return Outcome.EXTRACTED;
    }

    /**
     * Records an empty model of the running version for a document the extractor failed on (not taken again); nothing
     * for a document deleted meanwhile.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markEmpty(UUID documentId, Instant now) {
        if (!repository.lockDocument(documentId)) return;
        List<ChunkRow> chunks = repository.chunks(documentId);
        String digest = SystemModelPlan.digest(chunks.stream().map(ChunkRow::sha256).toList());
        repository.replace(documentId, new ExtractedModel(List.of(), List.of(), List.of(), true), chunks, digest,
                SystemModelExtractor.VERSION, now);
        meters.counter("verso.document.system_model.runs", "outcome", "failed").increment();
    }
}
