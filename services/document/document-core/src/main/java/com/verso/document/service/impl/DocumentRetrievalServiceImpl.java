package com.verso.document.service.impl;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.config.DocumentProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.RetrievalRepository;
import com.verso.document.repository.RetrievalRepository.Candidate;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DocumentRetrieval} (phase 5, ADR-0022): the question is embedded with the documents' own model, outside any
 * transaction (llm-rules 5.1); then, in one short read-only transaction, the hybrid query fuses the account's nearest
 * chunks with its best full-text matches, and the term shares of the returned passages are counted. A model that
 * does not answer is a 503; documents indexed with another model fail closed instead of returning noise (6.1).
 */
@Service
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentRetrievalServiceImpl implements DocumentRetrieval {

    private static final ExecutorService VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

    private final RetrievalRepository repository;
    private final EmbeddingModel embeddingModel;
    private final DocumentProperties properties;
    private final TransactionTemplate readOnly;
    private final Semaphore embeddingSlots;

    public DocumentRetrievalServiceImpl(RetrievalRepository repository, EmbeddingModel embeddingModel,
                                        DocumentProperties properties, TransactionTemplate transaction) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.readOnly = new TransactionTemplate(transaction.getTransactionManager());
        this.readOnly.setReadOnly(true);
        this.embeddingSlots = new Semaphore(properties.retrieval().embeddingConcurrency());
    }

    /**
     * Bounded in concurrency and time (ADR-0008: 10 s; phase 5 review R2/R3): a hanging model answers 503 quickly
     * instead of holding the request for the 90 s HTTP read timeout the ingestion batches need. On timeout the call is
     * interrupted, which cancels the HTTP request (phase 8, docs/capacity.md); it keeps its slot until it really ends,
     * so a hanging model cannot pile up more calls than the bulkhead allows.
     */
    private float[] embedQuestion(String question) {
        Duration timeout = properties.retrieval().embeddingTimeout();
        if (!embeddingSlots.tryAcquire()) {
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        }
        Future<float[]> call;
        // Whoever claims "started" owns the release: the task when it runs, the caller when it cancels a task that
        // never started (a cancelled FutureTask never runs its body; phase 8 review RS1).
        AtomicBoolean started = new AtomicBoolean();
        try {
            call = VIRTUAL.submit(() -> {
                if (!started.compareAndSet(false, true)) return null;
                try {
                    return embeddingModel.embed(question);
                } finally {
                    embeddingSlots.release();
                }
            });
        } catch (RuntimeException e) {
            embeddingSlots.release();
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        }
        try {
            return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            cancel(call, started);
            Thread.currentThread().interrupt();
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        } catch (TimeoutException e) {
            cancel(call, started);
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        } catch (ExecutionException e) {
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        }
    }

    private void cancel(Future<?> call, AtomicBoolean started) {
        call.cancel(true);
        if (started.compareAndSet(false, true)) embeddingSlots.release();
    }

    @Override
    public List<RetrievedPassage> search(String accountId, String question, int topK) {
        String model = properties.embeddingModel();
        if (repository.hasChunksOfAnotherModel(accountId, model)) {
            throw new DocumentServiceException(DocumentErrorCode.DOCUMENT_REINDEX_REQUIRED);
        }
        float[] vector = embedQuestion(question);
        if (vector.length != properties.embeddingDimensions()) {
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        }
        DocumentProperties.Retrieval settings = properties.retrieval();
        int vectorCandidates = Math.max(settings.vectorCandidates(), topK);
        return readOnly.execute(status -> {
            repository.prepareFilteredScan(vectorCandidates);
            List<Candidate> found = repository.hybrid(accountId, model, vector, question, vectorCandidates,
                    settings.lexicalCandidates(), settings.rrfK(), topK);
            Map<UUID, Double> shares = settings.lexicalCandidates() == 0 ? Map.of() : termShares(accountId, model, question, found);
            return found.stream().map(c -> new RetrievedPassage(c.documentId(), c.fileName(), c.pageNumber(), c.unit(),
                    c.content(), c.similarity(), shares.getOrDefault(c.chunkId(), 1.0))).toList();
        });
    }

    /**
     * Share of the account's searchable chunks that contain each passage's rarest question term (ADR-0022). Terms in
     * {@link DocumentRetrieval#TERM_SHARE_LIMIT} or more of the chunks are not counted further and read as 1.
     */
    private Map<UUID, Double> termShares(String accountId, String model, String question, List<Candidate> found) {
        if (found.isEmpty()) return Map.of();
        long total = repository.searchableChunks(accountId, model);
        if (total == 0) return Map.of();
        long cap = (long) Math.ceil(DocumentRetrieval.TERM_SHARE_LIMIT * total);
        Map<UUID, Double> shares = new HashMap<>();
        repository.rarestSharedTermCounts(accountId, model, question, found.stream().map(Candidate::chunkId).toList(), cap)
                .forEach((id, chunks) -> shares.put(id, chunks >= cap ? 1.0 : (double) chunks / total));
        return shares;
    }
}
