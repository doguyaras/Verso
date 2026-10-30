package com.verso.document.worker;

import com.verso.document.config.ConditionalOnIngestion;
import com.verso.document.config.ContextHeaderProperties;
import com.verso.document.config.DocumentProperties;
import com.verso.document.repository.ContextIndexRepository;
import com.verso.document.repository.ContextIndexRepository.ChunkRow;
import com.verso.document.repository.ContextIndexRepository.Source;
import com.verso.document.repository.IngestionRepository;
import com.verso.document.repository.IngestionRepository.StoredChunk;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-indexes READY documents whose chunks were embedded without a context header, or with an older version of the
 * header rules (ADR-0061): an upgraded installation re-embeds its old documents in the background, the documents stay
 * READY and searchable with their old vectors until each one is replaced, and startup never waits for it.
 *
 * <p>Per document: a short transaction leases it (document.context_locked_until, FOR NO KEY UPDATE SKIP LOCKED, so
 * instances take different documents); a read-only one reads its pages and chunks; the headers are built from what is
 * stored and the embedding model is called outside any transaction (reference 4.3), batch by batch, renewing the
 * lease; a last short transaction re-checks everything under the row lock and replaces the chunks under their own ids
 * (ContextIndexRepository.replace). Idempotent: a document finished meanwhile is left alone, and a finished document
 * is never taken again. The system model is extracted again afterwards in its own transaction, as after ingestion.
 *
 * <p>New uploads come first: a run is skipped while documents wait for ingestion or the embedding model's circuit is
 * open. Logs carry ids, counts and durations only (llm-rules 2.1).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnIngestion
public class ContextHeaderBackfillJob implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ContextHeaderBackfillJob.class);

    private static final String SHUTDOWN = "SHUTDOWN";

    /** What one document's run came to. */
    enum Outcome { REINDEXED, SKIPPED, MODEL_FAILED }

    private final ContextIndexRepository repository;
    private final IngestionRepository ingestion;
    private final IngestionWorker worker;
    private final SystemModelIndexer systemModel;
    private final EmbeddingModel embeddingModel;
    private final DocumentProperties documentProperties;
    private final ContextHeaderProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final MeterRegistry meters;
    private volatile boolean running;

    public ContextHeaderBackfillJob(ContextIndexRepository repository, IngestionRepository ingestion,
                                    IngestionWorker worker, SystemModelIndexer systemModel,
                                    EmbeddingModel embeddingModel, DocumentProperties documentProperties,
                                    ContextHeaderProperties properties, TransactionTemplate transaction, Clock clock,
                                    MeterRegistry meters) {
        this.repository = repository;
        this.ingestion = ingestion;
        this.worker = worker;
        this.systemModel = systemModel;
        this.embeddingModel = embeddingModel;
        this.documentProperties = documentProperties;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${verso.document.context-header.backfill-interval:30s}",
            initialDelayString = "${verso.document.context-header.backfill-interval:30s}")
    public void poll() {
        if (!properties.backfillEnabled() || !documentProperties.ingestion().enabled()) return;
        try {
            if (worker.isPaused() || ingestion.queueStats().pending() > 0) return;
            int done = runOnce();
            if (done > 0) log.info("Context header backfill ran: documents={}", done);
        } catch (RuntimeException e) {
            log.error("Context header backfill failed: code=CONTEXT_HEADER_BACKFILL_FAILED exceptionType={}",
                    e.getClass().getSimpleName());
        }
    }

    /** Re-indexes up to one batch of documents; returns how many were replaced. Tests call it directly. */
    public int runOnce() {
        int done = 0;
        for (int taken = 0; taken < properties.backfillBatch() && running; taken++) {
            Instant now = clock.instant();
            Optional<UUID> claimed = transaction.execute(status -> repository.claimNext(version(),
                    documentProperties.embeddingModel(), now, now.plus(properties.backfillLease())));
            if (claimed == null || claimed.isEmpty()) break;
            Outcome outcome = reindex(claimed.get());
            meters.counter("verso.document.context_header.backfill", "outcome",
                    outcome.name().toLowerCase(java.util.Locale.ROOT)).increment();
            if (outcome == Outcome.MODEL_FAILED) break;
            if (outcome == Outcome.REINDEXED) done++;
        }
        return done;
    }

    /** The running index version: the header rules and whether the header goes into the embedding. */
    private int version() {
        return ChunkContext.indexVersion(properties.embedHeader());
    }

    Outcome reindex(UUID documentId) {
        long started = System.nanoTime();
        Optional<Source> loaded = transaction.execute(status -> repository.load(documentId));
        if (loaded == null || loaded.isEmpty() || loaded.get().chunks().isEmpty()) return Outcome.SKIPPED;
        Source source = loaded.get();
        // The stored title when ingestion kept one; for a document from before ADR-0061, the same rules without the
        // PDF metadata title, which is gone with the file.
        String title = source.indexTitle() != null ? source.indexTitle()
                : DocumentTitle.of(source.pages(), null, source.fileName());
        List<ChunkRow> chunks = source.chunks();
        List<String> headers = ChunkContext.headers(title, source.pages(),
                chunks.stream().map(c -> new ChunkContext.Located(c.pageNumber(), c.content())).toList());
        List<StoredChunk> stored;
        try {
            stored = embed(documentId, chunks, headers);
        } catch (ModelFailure e) {
            transaction.executeWithoutResult(status -> repository.releaseLease(documentId));
            if (SHUTDOWN.equals(e.kind)) {
                log.info("Context header backfill released: documentId={} reason=SHUTDOWN", documentId);
            } else if (e.misconfigured) {
                log.error("Context header backfill stopped: code=CONTEXT_HEADER_MODEL_MISCONFIGURED documentId={} "
                        + "kind={}", documentId, e.kind);
            } else {
                log.warn("Context header backfill paused: documentId={} reason=MODEL_UNAVAILABLE exceptionType={}",
                        documentId, e.kind);
            }
            return Outcome.MODEL_FAILED;
        }
        List<UUID> ids = chunks.stream().map(ChunkRow::id).toList();
        Boolean replaced = transaction.execute(status -> repository.replace(documentId, version(),
                documentProperties.embeddingModel(), title, ids, stored));
        if (!Boolean.TRUE.equals(replaced)) {
            log.info("Context header backfill skipped: documentId={} reason=CHANGED_MEANWHILE", documentId);
            return Outcome.SKIPPED;
        }
        indexSystemModel(documentId);
        log.info("Context header backfill re-indexed: documentId={} chunks={} durationMs={}", documentId,
                chunks.size(), (System.nanoTime() - started) / 1_000_000);
        return Outcome.REINDEXED;
    }

    /** Gives the embedding server's slots back to questions between two batches; shutdown ends the wait at once. */
    private void pause() {
        long millis = properties.backfillPause().toMillis();
        if (millis == 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelFailure(false, SHUTDOWN);
        }
    }

    /** Each chunk's embedding input as the ingestion worker builds it; the text itself is stored unchanged. */
    private List<StoredChunk> embed(UUID documentId, List<ChunkRow> chunks, List<String> headers) {
        int batch = documentProperties.ingestion().embeddingBatch();
        List<StoredChunk> stored = new ArrayList<>(chunks.size());
        for (int from = 0; from < chunks.size(); from += batch) {
            if (!running) throw new ModelFailure(false, SHUTDOWN);
            if (from > 0) pause();
            int to = Math.min(chunks.size(), from + batch);
            List<String> inputs = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                inputs.add(ChunkContext.embeddingInput(headers.get(i), chunks.get(i).content(), properties.embedHeader()));
            }
            List<float[]> vectors;
            try {
                vectors = embeddingModel.embed(inputs);
            } catch (RuntimeException e) {
                throw new ModelFailure(IngestionWorker.isNonTransient(e), e.getClass().getSimpleName());
            }
            if (vectors.size() != inputs.size()) throw new ModelFailure(true, "WRONG_COUNT");
            for (int i = from; i < to; i++) {
                float[] vector = vectors.get(i - from);
                // Mixing vectors of another shape would make the document incomparable (llm-rules 6.1).
                if (vector.length != documentProperties.embeddingDimensions()) {
                    throw new ModelFailure(true, "WRONG_DIMENSIONS");
                }
                ChunkRow chunk = chunks.get(i);
                stored.add(new StoredChunk(chunk.pageNumber(), chunk.chunkIndex(), chunk.content(), vector,
                        headers.get(i)));
            }
            Instant until = clock.instant().plus(properties.backfillLease());
            transaction.executeWithoutResult(status -> repository.extendLease(documentId, until));
        }
        return stored;
    }

    /** Replacing the chunks dropped the system model read from them; a failure is left to its own backfill job. */
    private void indexSystemModel(UUID documentId) {
        try {
            transaction.executeWithoutResult(status -> systemModel.index(documentId, clock.instant()));
        } catch (RuntimeException e) {
            log.error("System model extraction failed: code=SYSTEM_MODEL_EXTRACTION_FAILED documentId={} exceptionType={}",
                    documentId, e.getClass().getSimpleName());
        }
    }

    // --- lifecycle: no new document after shutdown began; the one in progress is handed back between batches

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The embedding model failed (or shutdown began); misconfigured = it will keep failing until reconfigured. */
    private static final class ModelFailure extends RuntimeException {
        private final boolean misconfigured;
        private final String kind;

        ModelFailure(boolean misconfigured, String kind) {
            super(kind, null, false, false);
            this.misconfigured = misconfigured;
            this.kind = kind;
        }
    }
}
