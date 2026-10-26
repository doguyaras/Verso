package com.verso.qa.service;

import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.KnowledgeMemory;
import com.verso.qa.config.QaKnowledgeProperties;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.KnowledgeCardRepository.CardRow;
import com.verso.qa.repository.KnowledgeCardRepository.CitationRow;
import com.verso.qa.service.CitationExtractor.Extracted;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import com.verso.qa.service.KnowledgeCardRules.BindingState;
import com.verso.qa.repository.KnowledgeCardRepository.Candidate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * The serving side of the answer memory (ADR-0049). Before retrieval, with the question's own vector:
 *
 * <ol>
 *   <li>the nearest VERIFIED, unexpired cards of the current embedding model (exact cosine, qa schema);</li>
 *   <li>only those at or above {@code verso.qa.knowledge.match-threshold};</li>
 *   <li>for each, in order: every bound chunk is read again <b>with the caller's principals</b> (ADR-0028) and must
 *       still carry its hash. A caller who cannot read every cited document, or a source that changed, gets nothing
 *       from that card (no existence oracle: the normal path answers as if the card did not exist).</li>
 * </ol>
 *
 * The served answer is the verified text; its citations are mapped on the server to the cited pages exactly like a
 * model answer's (llm-rules 3.3, {@link CitationExtractor}), with excerpts from the reader-visible text. Never STALE,
 * DISPUTED, ARCHIVED or expired cards. A database failure of the lookup falls back to the normal path (logged with
 * the exception type only). Logs carry ids and counts, never the question or the answer (llm-rules 2.1).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AnswerMemory {

    private static final Logger log = LoggerFactory.getLogger(AnswerMemory.class);

    /** A card that may answer this caller now. */
    public record Served(UUID cardId, int cycle, String answer, List<Citation> citations, KnowledgeMemory memory) {

        @Override
        public String toString() {
            return "Served[cardId=" + cardId + "]";
        }
    }

    private final KnowledgeCardRepository cards;
    private final DocumentProvenance provenance;
    private final CitationExtractor extractor;
    private final QaKnowledgeProperties properties;
    private final Clock clock;
    private final MeterRegistry registry;
    private final Map<String, Counter> lookups = new ConcurrentHashMap<>();

    public AnswerMemory(KnowledgeCardRepository cards, DocumentProvenance provenance, CitationExtractor extractor,
                        QaKnowledgeProperties properties, Clock clock, MeterRegistry registry) {
        this.cards = cards;
        this.provenance = provenance;
        this.extractor = extractor;
        this.properties = properties;
        this.clock = clock;
        this.registry = registry;
        for (String outcome : List.of("served", "no_match", "not_readable", "failed")) counter(outcome);
    }

    /** False: the question path is exactly the one before ADR-0049 (one embedding inside the search). */
    public boolean enabled() {
        return properties.enabled();
    }

    public Optional<Served> lookup(Reader reader, QuestionEmbedding embedding) {
        Instant now = clock.instant();
        try {
            List<Candidate> matching = KnowledgeCardRules.matching(
                    cards.nearestVerified(embedding.vector(), embedding.model(), now, properties.matchCandidates()),
                    properties.matchThreshold());
            if (matching.isEmpty()) {
                counter("no_match").increment();
                return Optional.empty();
            }
            for (Candidate candidate : matching) {
                Optional<Served> served = serve(reader, candidate.cardId(), now);
                if (served.isPresent()) {
                    counter("served").increment();
                    return served;
                }
            }
            counter("not_readable").increment();
            return Optional.empty();
        } catch (DataAccessException e) {
            counter("failed").increment();
            log.warn("Answer memory lookup failed: outcome=fallback exceptionType={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<Served> serve(Reader reader, UUID cardId, Instant now) {
        Optional<CardRow> found = cards.find(cardId);
        if (found.isEmpty()) return Optional.empty();
        CardRow card = found.get();
        // Read again after the lookup: a card that went stale or was disputed meanwhile is never served.
        if (!KnowledgeCardRules.servable(card.state(), card.expiresAt(), now)) return Optional.empty();
        List<Binding> bindings = cards.bindings(cardId);
        List<CitationRow> citations = cards.citations(cardId);
        Set<SourcePage> pages = new LinkedHashSet<>();
        bindings.forEach(b -> pages.add(b.page()));
        citations.forEach(c -> pages.add(new SourcePage(c.documentId(), c.page())));
        if (pages.isEmpty() || pages.size() > DocumentProvenance.MAX_PAGES) return Optional.empty();
        // The caller's own principals: a page they cannot read is simply absent, and the binding then breaks.
        List<SourceChunk> visible = provenance.sourceChunks(reader, pages);
        if (KnowledgeCardRules.check(bindings, visible) != BindingState.INTACT) return Optional.empty();
        Map<SourcePage, List<SourceChunk>> byPage = visible.stream()
                .collect(Collectors.groupingBy(SourceChunk::page, LinkedHashMap::new, Collectors.toList()));
        // Every cited page must be readable too (a citation page without a binding would otherwise slip through).
        for (CitationRow citation : citations) {
            if (!byPage.containsKey(new SourcePage(citation.documentId(), citation.page()))) return Optional.empty();
        }
        Extracted extracted = extractor.extract(card.answer(), passages(citations, byPage));
        if (extracted.citations().isEmpty()) return Optional.empty();
        log.info("Answer memory hit: cardId={} cycle={} citations={}", cardId, card.cycle(), extracted.citations().size());
        return Optional.of(new Served(cardId, card.cycle(), extracted.answer(), extracted.citations(),
                new KnowledgeMemory(cardId, card.verifiedAt(), card.expiresAt())));
    }

    /**
     * The passages the card's markers point at, numbered as in the stored answer: the reader-visible text of each cited
     * page. Numbers without a citation (the model left some passages uncited) are empty placeholders, never cited.
     */
    static List<RetrievedPassage> passages(List<CitationRow> citations, Map<SourcePage, List<SourceChunk>> byPage) {
        int max = citations.stream().mapToInt(CitationRow::number).max().orElse(0);
        Map<Integer, CitationRow> byNumber = citations.stream()
                .collect(Collectors.toMap(CitationRow::number, c -> c, (a, b) -> a));
        List<RetrievedPassage> passages = new ArrayList<>(max);
        for (int number = 1; number <= max; number++) {
            CitationRow citation = byNumber.get(number);
            if (citation == null) {
                passages.add(new RetrievedPassage(new UUID(0, 0), "", 1, SourceUnit.PAGE, "", 0));
                continue;
            }
            List<SourceChunk> chunks = byPage.get(new SourcePage(citation.documentId(), citation.page()));
            SourceChunk first = chunks.getFirst();
            String text = chunks.stream().map(SourceChunk::content).collect(Collectors.joining("\n"));
            passages.add(new RetrievedPassage(citation.documentId(), first.fileName(), citation.page(), first.unit(), text,
                    1.0, 1.0, first.collectionId(), first.collectionName(), first.chunkId()));
        }
        return passages;
    }

    private Counter counter(String outcome) {
        return lookups.computeIfAbsent(outcome, o -> Counter.builder("verso.qa.knowledge.lookups").tag("outcome", o)
                .description("Answer memory lookups by outcome (ADR-0049)").register(registry));
    }
}
