package com.verso.document.service.impl;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.config.DocumentProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.RetrievalRepository;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DocumentRetrieval} (phase 5): the question is embedded with the documents' own model, outside any transaction
 * (llm-rules 5.1), then the nearest chunks of the account are read in one short read-only transaction. A model that
 * does not answer is a 503; documents indexed with another model fail closed instead of returning noise (6.1).
 */
@Service
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentRetrievalServiceImpl implements DocumentRetrieval {

    private static final Executor VIRTUAL = Executors.newVirtualThreadPerTaskExecutor();

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
     * instead of holding the request for the 90 s HTTP read timeout the ingestion batches need. The abandoned call
     * keeps its slot until it really ends, so a hanging model cannot pile up more calls than the bulkhead allows.
     */
    private float[] embedQuestion(String question) {
        Duration timeout = properties.retrieval().embeddingTimeout();
        if (!embeddingSlots.tryAcquire()) {
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        }
        CompletableFuture<float[]> call;
        try {
            call = CompletableFuture.supplyAsync(() -> embeddingModel.embed(question), VIRTUAL);
        } catch (RuntimeException e) {
            embeddingSlots.release();
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        }
        call.whenComplete((result, error) -> embeddingSlots.release());
        try {
            return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        } catch (ExecutionException | TimeoutException e) {
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        }
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
        List<RetrievedPassage> passages = readOnly.execute(status -> {
            repository.prepareFilteredScan(topK);
            return repository.nearest(accountId, model, vector, topK);
        });
        // The iterative scan returns rows in relaxed order: sort by similarity, best first.
        return passages.stream().sorted(Comparator.comparingDouble(RetrievedPassage::similarity).reversed()).toList();
    }
}
