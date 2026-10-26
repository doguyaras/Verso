package com.verso.document.service;

import com.verso.document.config.SystemModelProperties;
import com.verso.document.repository.RetrievalRepository;
import com.verso.document.repository.RetrievalRepository.Candidate;
import com.verso.document.repository.SystemModelRepository;
import com.verso.document.systemmodel.QuestionKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Retrieval enrichment of the system model (ADR-0054): when a question names a known CODE, TERM or SYSTEM ("WMS-204
 * ne demek?", "FEFO nedir"), the chunks that define it join the candidates, after the fused ones and never in their
 * place. Bounded ({@code enrichment-max-chunks}), inside the search's own read-only transaction, two indexed reads, no
 * model call; the same reader filter as the search (ADR-0028), so it can only add passages the reader could have found.
 * Off by default until measured on the customer's set (verso.document.system-model.enrichment-enabled).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SystemModelEnrichment {

    private final SystemModelRepository systemModel;
    private final RetrievalRepository retrieval;
    private final SystemModelProperties properties;
    private final MeterRegistry meters;

    public SystemModelEnrichment(SystemModelRepository systemModel, RetrievalRepository retrieval,
                                 SystemModelProperties properties, MeterRegistry meters) {
        this.systemModel = systemModel;
        this.retrieval = retrieval;
        this.properties = properties;
        this.meters = meters;
    }

    public boolean enabled() {
        return properties.enrichmentEnabled();
    }

    /** The extra candidates for {@code question}; empty when off, when it names nothing known, or all are found. */
    public List<Candidate> extra(String[] principals, String model, float[] vector, String question,
                                 List<Candidate> found) {
        if (!properties.enrichmentEnabled()) return List.of();
        List<String> keys = QuestionKeys.of(question);
        List<UUID> have = found.stream().map(Candidate::chunkId).toList();
        List<UUID> ids = systemModel.definingChunks(principals, model, keys, have, properties.enrichmentMaxChunks());
        List<Candidate> extra = ids.isEmpty() ? List.of() : retrieval.byIds(principals, model, vector, ids);
        meters.counter("verso.document.retrieval.enrichment", "outcome", extra.isEmpty() ? "none" : "added")
                .increment();
        return extra;
    }
}
