package com.verso.document.worker;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.config.DocumentProperties;
import com.verso.document.repository.IngestionRepository.Claim;
import com.verso.document.repository.IngestionRepository.StoredChunk;
import com.verso.document.worker.IngestionTransactionService.LostClaimException;
import com.verso.document.worker.PageChunker.Chunk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns PENDING documents into READY ones (ADR-0003, ADR-0011): claim → pages (parsed once, then the PDF is dropped)
 * → chunks → embeddings in batches, renewing the lease after each → store. Runs on every instance; SKIP LOCKED and the
 * claim token make that safe (reference 11.1). Logs carry ids, counts, durations and fixed reasons only, never text or
 * file names (llm-rules 2.1).
 *
 * <p>A failing embedding model is not the document's fault (phase 4 review R1/R2): the document goes back to PENDING
 * without spending an attempt, and the worker pauses all claims for a while, a circuit breaker for the one remote
 * dependency. A model that answers with an error that will not go away (unknown model, wrong dimensions) pauses longer
 * and logs an ERROR. On shutdown no new document is claimed and the one in progress is handed back the same way (R3).
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IngestionWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
    /** Spring AI's "will not succeed if repeated" type; matched by name (spring-ai-retry is not a dependency here). */
    static final String NON_TRANSIENT_AI = "org.springframework.ai.retry.NonTransientAiException";

    private final IngestionTransactionService tx;
    private final PdfTextExtractor extractor;
    private final PageChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final DocumentProperties properties;
    private final Clock clock;
    private volatile boolean running;
    private volatile Instant pausedUntil = Instant.MIN;

    public IngestionWorker(IngestionTransactionService tx, PdfTextExtractor extractor, PageChunker chunker,
                           EmbeddingModel embeddingModel, DocumentProperties properties, Clock clock) {
        this.tx = tx;
        this.extractor = extractor;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.clock = clock;
    }

    /** One failure must not stop the next poll (reference 23.4 scheduler wrapper); empty polls are not logged. */
    @Scheduled(fixedDelayString = "${verso.document.ingestion.poll-interval-ms:5000}",
            initialDelayString = "${verso.document.ingestion.poll-interval-ms:5000}")
    public void poll() {
        if (!properties.ingestion().enabled()) return;
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Ingestion poll failed: exceptionType={}", e.getClass().getSimpleName());
        }
    }

    /** Processes due documents until none is left, the per-poll limit is reached, the model is paused or shutdown began. */
    public int runOnce() {
        int claimed = 0;
        while (claimed < properties.ingestion().maxDocumentsPerPoll() && running && !paused()) {
            Instant now = clock.instant();
            Optional<Claim> claim = tx.claimNext(now, now.plus(properties.ingestion().lease()));
            if (claim.isEmpty()) break;
            claimed++;
            process(claim.get());
        }
        return claimed;
    }

    private void process(Claim claim) {
        long started = System.nanoTime();
        try {
            if (claim.attempts() > properties.ingestion().maxAttempts()) {
                // Claimed again and again without an outcome: the worker keeps dying on this file (poison).
                fail(claim, DocumentFailureReason.PROCESSING_FAILED, started);
                return;
            }
            List<String> pages = tx.loadPages(claim);
            if (pages.isEmpty()) {
                byte[] pdf = tx.loadFile(claim).orElseThrow(() -> new IngestionRejectedException(DocumentFailureReason.NOT_A_PDF));
                pages = extractor.extract(pdf);
                tx.storePages(claim, pages, clock.instant());
            }
            List<Chunk> chunks = chunker.chunk(pages);
            if (chunks.isEmpty()) throw new IngestionRejectedException(DocumentFailureReason.NO_TEXT);
            List<StoredChunk> stored = embed(claim, chunks);
            tx.complete(claim, pages.size(), stored, properties.embeddingModel(), clock.instant());
            log.info("Document ingestion finished: documentId={} pages={} chunks={} attempts={} durationMs={} outcome=READY",
                    claim.documentId(), pages.size(), chunks.size(), claim.attempts(), elapsedMs(started));
        } catch (IngestionRejectedException e) {
            fail(claim, e.reason(), started);
        } catch (ModelFailure e) {
            pause(e);
            release(claim, e.misconfigured ? "MODEL_MISCONFIGURED" : "MODEL_UNAVAILABLE", started);
        } catch (Stopping e) {
            release(claim, "SHUTDOWN", started);
        } catch (LostClaimException e) {
            log.warn("Document ingestion abandoned: documentId={} reason=CLAIM_LOST durationMs={} outcome=lost",
                    claim.documentId(), elapsedMs(started));
        } catch (RuntimeException e) {
            retryOrFail(claim, e, started);
        }
    }

    private List<StoredChunk> embed(Claim claim, List<Chunk> chunks) {
        int batch = properties.ingestion().embeddingBatch();
        List<StoredChunk> stored = new ArrayList<>(chunks.size());
        for (int from = 0; from < chunks.size(); from += batch) {
            if (!running) throw new Stopping();
            List<Chunk> slice = chunks.subList(from, Math.min(chunks.size(), from + batch));
            List<float[]> vectors;
            try {
                vectors = embeddingModel.embed(slice.stream().map(Chunk::content).toList());
            } catch (RuntimeException e) {
                throw new ModelFailure(isNonTransient(e), e.getClass().getSimpleName());
            }
            // A batch can take a minute on a CPU host: check again before spending more time on this document.
            if (!running) throw new Stopping();
            if (vectors.size() != slice.size()) throw new ModelFailure(true, "WRONG_COUNT");
            for (int i = 0; i < slice.size(); i++) {
                float[] vector = vectors.get(i);
                // Another model or a wrong configuration: storing it would mix incomparable vectors (llm-rules 6.1).
                if (vector.length != properties.embeddingDimensions()) throw new ModelFailure(true, "WRONG_DIMENSIONS");
                Chunk chunk = slice.get(i);
                stored.add(new StoredChunk(chunk.pageNumber(), chunk.chunkIndex(), chunk.content(), vector));
            }
            Instant now = clock.instant();
            tx.extendLease(claim, now, now.plus(properties.ingestion().lease()));
        }
        return stored;
    }

    private void pause(ModelFailure failure) {
        Duration pause = failure.misconfigured ? properties.ingestion().modelMisconfiguredPause()
                : properties.ingestion().modelUnavailablePause();
        pausedUntil = clock.instant().plus(pause);
        if (failure.misconfigured) {
            log.error("Embedding model misconfigured: kind={} ingestion paused for {}s", failure.kind, pause.toSeconds());
        } else {
            log.warn("Embedding model unavailable: exceptionType={} ingestion paused for {}s", failure.kind, pause.toSeconds());
        }
    }

    private boolean paused() {
        return clock.instant().isBefore(pausedUntil);
    }

    /** True while the embedding model circuit is open (no claims); for health reporting and tests. */
    public boolean isPaused() {
        return paused();
    }

    /** Back to PENDING without spending an attempt: neither a model outage nor a deploy is the document's fault. */
    private void release(Claim claim, String reason, long started) {
        try {
            tx.release(claim, clock.instant());
            log.info("Document ingestion released: documentId={} reason={} durationMs={} outcome=released",
                    claim.documentId(), reason, elapsedMs(started));
        } catch (LostClaimException lost) {
            log.warn("Document ingestion abandoned: documentId={} reason=CLAIM_LOST outcome=lost", claim.documentId());
        } catch (RuntimeException e) {
            // Shutdown closes the pool after this phase; the lease then hands the document to the next worker.
            log.warn("Document ingestion release failed: documentId={} exceptionType={} outcome=lease",
                    claim.documentId(), e.getClass().getSimpleName());
        }
    }

    private void fail(Claim claim, DocumentFailureReason reason, long started) {
        try {
            tx.fail(claim, reason, clock.instant());
            log.warn("Document ingestion failed: documentId={} reason={} attempts={} durationMs={} outcome=FAILED",
                    claim.documentId(), reason, claim.attempts(), elapsedMs(started));
        } catch (LostClaimException lost) {
            log.warn("Document ingestion abandoned: documentId={} reason=CLAIM_LOST outcome=lost", claim.documentId());
        }
    }

    private void retryOrFail(Claim claim, RuntimeException cause, long started) {
        if (claim.attempts() >= properties.ingestion().maxAttempts()) {
            try {
                tx.fail(claim, DocumentFailureReason.PROCESSING_FAILED, clock.instant());
                log.error("Document ingestion failed: documentId={} reason=PROCESSING_FAILED exceptionType={} attempts={} "
                        + "durationMs={} outcome=FAILED", claim.documentId(), cause.getClass().getSimpleName(),
                        claim.attempts(), elapsedMs(started));
            } catch (LostClaimException lost) {
                log.warn("Document ingestion abandoned: documentId={} reason=CLAIM_LOST outcome=lost", claim.documentId());
            }
            return;
        }
        Duration delay = backoff(claim.attempts());
        try {
            Instant now = clock.instant();
            tx.retryLater(claim, now.plus(delay), now);
            log.warn("Document ingestion will retry: documentId={} exceptionType={} attempts={} retryInSeconds={} "
                    + "outcome=retry", claim.documentId(), cause.getClass().getSimpleName(), claim.attempts(),
                    delay.toSeconds());
        } catch (LostClaimException lost) {
            log.warn("Document ingestion abandoned: documentId={} reason=CLAIM_LOST outcome=lost", claim.documentId());
        }
    }

    /** retryBackoff · 2^(attempts-1), capped at maxRetryBackoff. */
    Duration backoff(int attempts) {
        Duration first = properties.ingestion().retryBackoff();
        Duration max = properties.ingestion().maxRetryBackoff();
        Duration delay = first;
        for (int i = 1; i < attempts && delay.compareTo(max) < 0; i++) delay = delay.multipliedBy(2);
        return delay.compareTo(max) > 0 ? max : delay;
    }

    static boolean isNonTransient(Throwable error) {
        for (Class<?> type = error.getClass(); type != null; type = type.getSuperclass()) {
            if (NON_TRANSIENT_AI.equals(type.getName())) return true;
        }
        return false;
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    // --- lifecycle: stopped before the web server and the connection pool go away (highest phase stops first)

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

    /** The embedding model failed; misconfigured = it will keep failing until someone changes the configuration. */
    private static final class ModelFailure extends RuntimeException {
        private final boolean misconfigured;
        private final String kind;

        ModelFailure(boolean misconfigured, String kind) {
            super(kind, null, false, false);
            this.misconfigured = misconfigured;
            this.kind = kind;
        }
    }

    /** Shutdown began between two embedding batches. */
    private static final class Stopping extends RuntimeException {
        Stopping() {
            super("stopping", null, false, false);
        }
    }
}
