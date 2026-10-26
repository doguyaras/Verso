package com.verso.document.service.impl;

import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.document.config.DocumentProperties;
import com.verso.document.repository.ProvenanceRepository;
import com.verso.document.repository.RetrievalRepository;
import com.verso.document.repository.RetrievalRepository.Candidate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DocumentProvenance} (ADR-0049). Short read-only transactions, no model call: the hashes come from the stored
 * text, and the nightly ranking uses the vector the qa module stored with its verified answer. The ranking searches as
 * the cited documents' audience (the union of their reader principals), so it measures what a member of that audience
 * would retrieve; it uses the fused order only (the optional reranker of ADR-0043 is a remote call and stays out).
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentProvenanceServiceImpl implements DocumentProvenance {

    private final ProvenanceRepository repository;
    private final RetrievalRepository retrieval;
    private final DocumentProperties properties;
    private final TransactionTemplate readOnly;

    public DocumentProvenanceServiceImpl(ProvenanceRepository repository, RetrievalRepository retrieval,
                                         DocumentProperties properties, TransactionTemplate transaction) {
        this.repository = repository;
        this.retrieval = retrieval;
        this.properties = properties;
        this.readOnly = new TransactionTemplate(transaction.getTransactionManager());
        this.readOnly.setReadOnly(true);
    }

    @Override
    public List<SourceChunk> sourceChunks(Reader reader, Collection<SourcePage> pages) {
        String[] principals = reader.principals().toArray(String[]::new);
        return lookup(pages, (bounded) -> repository.readable(principals, documents(bounded), pageNumbers(bounded)));
    }

    @Override
    public List<SourceChunk> sourceHashes(Collection<SourcePage> pages) {
        return lookup(pages, (bounded) -> repository.hashes(documents(bounded), pageNumbers(bounded)));
    }

    @Override
    public List<UUID> rankForAudience(Collection<UUID> audienceOf, String question, QuestionEmbedding embedding, int topK) {
        Set<UUID> documents = new LinkedHashSet<>(audienceOf.stream().filter(Objects::nonNull).toList());
        if (documents.isEmpty()) return List.of();
        if (documents.size() > MAX_PAGES) throw new IllegalArgumentException("at most " + MAX_PAGES + " documents");
        if (topK < 1 || topK > 50) throw new IllegalArgumentException("topK must be 1..50");
        String model = properties.embeddingModel();
        float[] vector = embedding.vector();
        // A vector of another model would be compared with the wrong space: nothing is comparable, nothing is ranked.
        if (!model.equals(embedding.model()) || vector.length != properties.embeddingDimensions()) return List.of();
        DocumentProperties.Retrieval settings = properties.retrieval();
        int vectorCandidates = Math.max(settings.vectorCandidates(), topK);
        List<UUID> ranked = readOnly.execute(status -> {
            String[] principals = repository.principalsOf(documents);
            if (principals.length == 0) return List.of();
            retrieval.prepareFilteredScan(vectorCandidates);
            return retrieval.hybrid(principals, model, vector, question, vectorCandidates, settings.lexicalCandidates(),
                    settings.rrfK(), topK).stream().map(Candidate::chunkId).toList();
        });
        return ranked == null ? List.of() : ranked;
    }

    private List<SourceChunk> lookup(Collection<SourcePage> pages, Function<Set<SourcePage>, List<SourceChunk>> query) {
        Set<SourcePage> wanted = new LinkedHashSet<>(pages.stream().filter(Objects::nonNull).toList());
        if (wanted.isEmpty()) return List.of();
        if (wanted.size() > MAX_PAGES) throw new IllegalArgumentException("at most " + MAX_PAGES + " pages");
        List<SourceChunk> found = readOnly.execute(status -> query.apply(wanted));
        // The statement matches documents and page numbers separately; only the exact pairs asked for are returned.
        return found == null ? List.of() : found.stream().filter(chunk -> wanted.contains(chunk.page())).toList();
    }

    private static Set<UUID> documents(Set<SourcePage> pages) {
        Set<UUID> ids = new LinkedHashSet<>();
        pages.forEach(page -> ids.add(page.documentId()));
        return ids;
    }

    private static Set<Integer> pageNumbers(Set<SourcePage> pages) {
        Set<Integer> numbers = new LinkedHashSet<>();
        pages.forEach(page -> numbers.add(page.pageNumber()));
        return numbers;
    }
}
