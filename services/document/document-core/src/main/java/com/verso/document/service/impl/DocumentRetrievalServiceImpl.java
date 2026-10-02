package com.verso.document.service.impl;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.config.DocumentProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.RetrievalRepository;
import java.util.Comparator;
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

    private final RetrievalRepository repository;
    private final EmbeddingModel embeddingModel;
    private final DocumentProperties properties;
    private final TransactionTemplate readOnly;

    public DocumentRetrievalServiceImpl(RetrievalRepository repository, EmbeddingModel embeddingModel,
                                        DocumentProperties properties, TransactionTemplate transaction) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.readOnly = new TransactionTemplate(transaction.getTransactionManager());
        this.readOnly.setReadOnly(true);
    }

    @Override
    public List<RetrievedPassage> search(String accountId, String question, int topK) {
        String model = properties.embeddingModel();
        if (repository.hasChunksOfAnotherModel(accountId, model)) {
            throw new DocumentServiceException(DocumentErrorCode.DOCUMENT_REINDEX_REQUIRED);
        }
        float[] vector;
        try {
            vector = embeddingModel.embed(question);
        } catch (RuntimeException e) {
            throw new DocumentServiceException(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
        }
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
