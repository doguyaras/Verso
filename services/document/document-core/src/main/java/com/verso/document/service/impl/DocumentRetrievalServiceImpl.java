package com.verso.document.service.impl;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.Passage;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.config.DocumentProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.RetrievalRepository;
import com.verso.document.repository.RetrievalRepository.Candidate;
import com.verso.document.service.PassageReranker;
import com.verso.document.service.SystemModelEnrichment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
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
 * does not answer is a 503; documents indexed with another model fail closed instead of returning noise (6.1). With the
 * optional reranker (ADR-0043) the fused top-N go to a cross-encoder after the transaction and its best top-k are
 * returned; the answerability gate still reads the same passage as without it (see {@link #reranked}).
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
    private final PassageReranker reranker;
    private final SystemModelEnrichment enrichment;

    public DocumentRetrievalServiceImpl(RetrievalRepository repository, EmbeddingModel embeddingModel,
                                        DocumentProperties properties, TransactionTemplate transaction,
                                        PassageReranker reranker, SystemModelEnrichment enrichment) {
        this.repository = repository;
        this.reranker = reranker;
        this.enrichment = enrichment;
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
        // One code for the client (503, Retry-After), but the log says which it was: all slots taken (overload, the
        // model is fine), the model not answering in time, or the call failing. Otherwise an operator cannot tell a
        // busy minute from an outage (bug hunt 2026-10-30).
        if (!embeddingSlots.tryAcquire()) {
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE, "EMBEDDING_BUSY");
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
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE, "EMBEDDING_REJECTED");
        }
        try {
            return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            cancel(call, started);
            Thread.currentThread().interrupt();
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE, "EMBEDDING_INTERRUPTED");
        } catch (TimeoutException e) {
            cancel(call, started);
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE, "EMBEDDING_TIMEOUT");
        } catch (ExecutionException e) {
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE, "EMBEDDING_FAILED");
        }
    }

    private void cancel(Future<?> call, AtomicBoolean started) {
        call.cancel(true);
        if (started.compareAndSet(false, true)) embeddingSlots.release();
    }

    @Override
    public List<RetrievedPassage> search(Reader reader, String question, int topK) {
        // The model check first: a reader whose documents need a reindex costs no embedding call.
        refuseOtherModels(reader);
        return searchWith(reader, question, embed(question).vector(), topK);
    }

    @Override
    public QuestionEmbedding embed(String question) {
        float[] vector = embedQuestion(question);
        if (vector.length != properties.embeddingDimensions()) {
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE, "EMBEDDING_WRONG_DIMENSIONS");
        }
        return new QuestionEmbedding(properties.embeddingModel(), vector);
    }

    @Override
    public List<RetrievedPassage> search(Reader reader, String question, QuestionEmbedding embedding, int topK) {
        float[] vector = embedding.vector();
        if (!properties.embeddingModel().equals(embedding.model()) || vector.length != properties.embeddingDimensions()) {
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        }
        refuseOtherModels(reader);
        return searchWith(reader, question, vector, topK);
    }

    private void refuseOtherModels(Reader reader) {
        if (repository.hasChunksOfAnotherModel(reader.principals().toArray(String[]::new), properties.embeddingModel())) {
            throw new DocumentServiceException(DocumentErrorCode.DOCUMENT_REINDEX_REQUIRED);
        }
    }

    private List<RetrievedPassage> searchWith(Reader reader, String question, float[] vector, int topK) {
        // Computed once from the token (ADR-0028); every statement below filters on exactly this set.
        String[] principals = reader.principals().toArray(String[]::new);
        String model = properties.embeddingModel();
        DocumentProperties.Retrieval settings = properties.retrieval();
        int vectorCandidates = Math.max(settings.vectorCandidates(), topK);
        // The reranker reads more fused candidates than top-k; the two candidate lists themselves stay as they are.
        int limit = reranker.enabled() ? Math.max(topK, reranker.candidates()) : topK;
        List<RetrievedPassage> fused = readOnly.execute(status -> {
            repository.prepareFilteredScan(vectorCandidates);
            List<Candidate> fusedOnly = repository.hybrid(principals, model, vector, question, vectorCandidates,
                    settings.lexicalCandidates(), settings.rrfK(), limit);
            // ADR-0054 (off by default): defining chunks of a code, term or system the question names, after the
            // fused ones; with the reranker they compete like every other candidate.
            List<Candidate> extra = enrichment.extra(principals, model, vector, question, fusedOnly);
            List<Candidate> found = extra.isEmpty() ? fusedOnly
                    : java.util.stream.Stream.concat(fusedOnly.stream(), extra.stream()).toList();
            Map<UUID, Double> shares = settings.lexicalCandidates() == 0 ? Map.of() : termShares(principals, model, question, found);
            return found.stream().map(c -> new RetrievedPassage(c.documentId(), c.fileName(), c.pageNumber(), c.unit(),
                    c.content(), c.similarity(), shares.getOrDefault(c.chunkId(), 1.0), c.collectionId(),
                    c.collectionName(), c.chunkId())).toList();
        });
        if (fused == null || !reranker.enabled()) return fused;
        List<RetrievedPassage> base = fused.subList(0, Math.min(topK, fused.size()));
        if (fused.size() <= 1) return base;
        // The remote call runs after the transaction: no connection is held while the sidecar works (llm-rules 5.1).
        return reranker.order(question, fused.stream().map(RetrievedPassage::content).toList())
                .map(order -> reranked(fused, order, topK))
                .orElse(base);
    }

    /**
     * The reranked top-k (ADR-0043), built so that the answerability gate decides exactly as without the reranker. The
     * gate reads the most similar passage (cosine) of the returned list (ADR-0022); that passage, the "evidence", is the
     * most similar one of the fused top-k. It is always returned, and no other passage at least as similar is, so it
     * stays the most similar one. Everything else follows the reranker's order. A passage the reranker would add from
     * beyond the fused top-k that is more similar than the evidence is left out: it would change the gate's input
     * without a re-calibration. The list is in reranked order, so citation numbers follow the reranker.
     *
     * @param fused the fused candidates, best first
     * @param order a permutation of the candidates' indexes, most relevant first
     */
    static List<RetrievedPassage> reranked(List<RetrievedPassage> fused, List<Integer> order, int topK) {
        RetrievedPassage evidence = fused.getFirst();
        for (RetrievedPassage p : fused.subList(0, Math.min(topK, fused.size()))) {
            if (p.similarity() > evidence.similarity()) evidence = p;
        }
        List<RetrievedPassage> result = new ArrayList<>(topK);
        for (int index : order) {
            if (result.size() == topK) break;
            RetrievedPassage p = fused.get(index);
            if (p == evidence || p.similarity() < evidence.similarity()) result.add(p);
        }
        if (!result.contains(evidence)) {
            if (result.size() == topK) result.removeLast();
            result.add(evidence);
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<Passage> passage(Reader reader, UUID passageId) {
        if (passageId == null) return Optional.empty();
        String[] principals = reader.principals().toArray(String[]::new);
        Optional<Candidate> found = readOnly.execute(status -> repository.passage(principals, passageId));
        return found == null ? Optional.empty() : found.map(c -> new Passage(c.chunkId(), c.documentId(), c.fileName(),
                c.pageNumber(), c.unit(), c.content(), c.collectionId(), c.collectionName()));
    }

    @Override
    public boolean rerankerEnabled() {
        return reranker.enabled();
    }

    /** ADR-0060: no transaction is open here; the reranker's own guards bound the call (ADR-0043). */
    @Override
    public OptionalDouble relevance(String question, String text) {
        if (question == null || text == null || question.isBlank() || text.isBlank()) return OptionalDouble.empty();
        return reranker.score(question, text);
    }

    /**
     * Share of the reader's searchable chunks that contain each passage's rarest question term (ADR-0022). Terms in
     * {@link DocumentRetrieval#TERM_SHARE_LIMIT} or more of the chunks are not counted further and read as 1.
     */
    private Map<UUID, Double> termShares(String[] principals, String model, String question, List<Candidate> found) {
        if (found.isEmpty()) return Map.of();
        long total = repository.searchableChunks(principals, model);
        if (total == 0) return Map.of();
        long cap = (long) Math.ceil(DocumentRetrieval.TERM_SHARE_LIMIT * total);
        Map<UUID, Double> shares = new HashMap<>();
        repository.rarestSharedTermCounts(principals, model, question, found.stream().map(Candidate::chunkId).toList(), cap)
                .forEach((id, chunks) -> shares.put(id, chunks >= cap ? 1.0 : (double) chunks / total));
        return shares;
    }
}
