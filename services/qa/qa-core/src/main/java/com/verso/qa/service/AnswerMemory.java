package com.verso.qa.service;

import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.DocumentRetrieval;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
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
 *   <li>ADR-0060: only those whose question carries every key fact of the new one ({@link KeyFacts}: numbers, amounts,
 *       dates, codes, quoted terms, negation). Cosine cannot tell "500.000 TL" from "50.000 TL"; this check can;</li>
 *   <li>for each, in order: every bound chunk is read again <b>with the caller's principals</b> (ADR-0028) and must
 *       still carry its hash. A caller who cannot read every cited document, or a source that changed, gets nothing
 *       from that card (no existence oracle: the normal path answers as if the card did not exist);</li>
 *   <li>ADR-0060, off by default: the reranker (ADR-0043) scores the pair (new question, card question); below
 *       {@code rerank-check.min-score}, or without a score at all, the card does not answer.</li>
 * </ol>
 *
 * The served answer is the verified text; its citations are mapped on the server to the cited pages exactly like a
 * model answer's (llm-rules 3.3, {@link CitationExtractor}), with excerpts from the reader-visible text. Never STALE,
 * DISPUTED, ARCHIVED or expired cards. A database failure of the lookup falls back to the normal path (logged with
 * the exception type only). A rejected card is counted by a fixed reason code ({@link Rejection}), never with the
 * question. Logs carry ids, counts and reason codes, never the question or the answer (llm-rules 2.1).
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

    /**
     * Why a card above the cosine threshold did not answer (ADR-0060). Fixed values: they reach a log line and the
     * {@code verso.qa.knowledge.rejections} metric, so the health and dashboard screens can show them.
     */
    public enum Rejection {
        /** A number of the question is not the card's ("3 gün" / "5 gün"). */
        KEY_FACT_NUMBER,
        /** An amount or its currency differs ("50.000 TL" / "500.000 TL"). */
        KEY_FACT_AMOUNT,
        /** A percentage differs ("%18" / "%8"). */
        KEY_FACT_PERCENT,
        /** A quantity or its unit differs ("30 gün" / "30 saat"). */
        KEY_FACT_QUANTITY,
        /** A date, month, year or time differs. */
        KEY_FACT_DATE,
        /** A code or reference differs ("P1" / "P3", "madde 7" / "madde 8"). */
        KEY_FACT_CODE,
        /** A quoted term of the question is not in the card's question. */
        KEY_FACT_QUOTE,
        /** One question is negated or excludes something ("değil", "olmayan", "hariç") and the other is not. */
        KEY_FACT_NEGATION,
        /** The reranker scored the pair below {@code rerank-check.min-score}. */
        RERANK_LOW,
        /** The reranker check is on but gave no score (off, skipped, failed): not confirmed, not served. */
        RERANK_UNAVAILABLE;

        static Rejection of(KeyFacts.Kind kind) {
            return valueOf("KEY_FACT_" + kind.name());
        }

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final KnowledgeCardRepository cards;
    private final DocumentProvenance provenance;
    private final DocumentRetrieval retrieval;
    private final CitationExtractor extractor;
    private final QaKnowledgeProperties properties;
    private final Clock clock;
    private final MeterRegistry registry;
    private final Map<String, Counter> lookups = new ConcurrentHashMap<>();
    private final Map<Rejection, Counter> rejections = new EnumMap<>(Rejection.class);

    public AnswerMemory(KnowledgeCardRepository cards, DocumentProvenance provenance, DocumentRetrieval retrieval,
                        CitationExtractor extractor, QaKnowledgeProperties properties, Clock clock,
                        MeterRegistry registry) {
        this.cards = cards;
        this.provenance = provenance;
        this.retrieval = retrieval;
        this.extractor = extractor;
        this.properties = properties;
        this.clock = clock;
        this.registry = registry;
        // A check that can never confirm would silently turn the answer memory off: refuse to start instead.
        if (properties.enabled() && properties.rerankCheck().enabled() && !retrieval.rerankerEnabled()) {
            throw new IllegalStateException(
                    "verso.qa.knowledge.rerank-check.enabled needs verso.document.reranker.enabled (ADR-0060)");
        }
        for (String outcome : List.of("served", "no_match", "rejected", "not_readable", "failed")) counter(outcome);
        for (Rejection reason : Rejection.values()) {
            rejections.put(reason, Counter.builder("verso.qa.knowledge.rejections").tag("reason", reason.tag())
                    .description("Verified cards above the match threshold that did not answer, by reason (ADR-0060)")
                    .register(registry));
        }
    }

    /** False: the question path is exactly the one before ADR-0049 (one embedding inside the search). */
    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * @param question the question the vector was made from (the search query of a follow-up); read for its key
     *                 facts and, with the rerank check on, sent to the internal reranker; never logged
     */
    public Optional<Served> lookup(Reader reader, QuestionEmbedding embedding, String question) {
        Instant now = clock.instant();
        try {
            List<Candidate> matching = KnowledgeCardRules.matching(
                    cards.nearestVerified(embedding.vector(), embedding.model(), now, properties.matchCandidates()),
                    properties.matchThreshold());
            if (matching.isEmpty()) {
                counter("no_match").increment();
                return Optional.empty();
            }
            KeyFacts asked = KeyFacts.of(question);
            boolean rejected = false;
            for (Candidate candidate : matching) {
                Attempt attempt = serve(reader, candidate.cardId(), now, question, asked);
                if (attempt.served() != null) {
                    counter("served").increment();
                    return Optional.of(attempt.served());
                }
                if (attempt.rejection() != null) {
                    rejected = true;
                    rejections.get(attempt.rejection()).increment();
                    log.info("Answer memory card rejected: cardId={} reason={}", candidate.cardId(),
                            attempt.rejection());
                }
            }
            counter(rejected ? "rejected" : "not_readable").increment();
            return Optional.empty();
        } catch (DataAccessException e) {
            counter("failed").increment();
            log.warn("Answer memory lookup failed: outcome=fallback exceptionType={}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** What one candidate gave: a served card, a rejection by the match checks, or neither (not servable here). */
    private record Attempt(Served served, Rejection rejection) {

        static final Attempt NOTHING = new Attempt(null, null);
    }

    private Attempt serve(Reader reader, UUID cardId, Instant now, String question, KeyFacts asked) {
        Optional<CardRow> found = cards.find(cardId);
        if (found.isEmpty()) return Attempt.NOTHING;
        CardRow card = found.get();
        // Read again after the lookup: a card that went stale or was disputed meanwhile is never served.
        if (!KnowledgeCardRules.servable(card.state(), card.expiresAt(), now)) return Attempt.NOTHING;
        // ADR-0060: before any further read, the in-process key-facts guard (no I/O).
        Optional<KeyFacts.Kind> conflict = asked.conflictWith(KeyFacts.of(card.question()));
        if (conflict.isPresent()) return new Attempt(null, Rejection.of(conflict.get()));
        List<Binding> bindings = cards.bindings(cardId);
        List<CitationRow> citations = cards.citations(cardId);
        Set<SourcePage> pages = new LinkedHashSet<>();
        bindings.forEach(b -> pages.add(b.page()));
        citations.forEach(c -> pages.add(new SourcePage(c.documentId(), c.page())));
        if (pages.isEmpty() || pages.size() > DocumentProvenance.MAX_PAGES) return Attempt.NOTHING;
        // The caller's own principals: a page they cannot read is simply absent, and the binding then breaks.
        List<SourceChunk> visible = provenance.sourceChunks(reader, pages);
        if (KnowledgeCardRules.check(bindings, visible) != BindingState.INTACT) return Attempt.NOTHING;
        Map<SourcePage, List<SourceChunk>> byPage = visible.stream()
                .collect(Collectors.groupingBy(SourceChunk::page, LinkedHashMap::new, Collectors.toList()));
        // Every cited page must be readable too (a citation page without a binding would otherwise slip through).
        for (CitationRow citation : citations) {
            if (!byPage.containsKey(new SourcePage(citation.documentId(), citation.page()))) return Attempt.NOTHING;
        }
        Extracted extracted = extractor.extract(card.answer(), passages(citations, byPage));
        if (extracted.citations().isEmpty()) return Attempt.NOTHING;
        // Last, as it is the only remote call: only a card this caller could otherwise be served is scored.
        Rejection reranked = rerankCheck(question, card.question());
        if (reranked != null) return new Attempt(null, reranked);
        log.info("Answer memory hit: cardId={} cycle={} citations={}", cardId, card.cycle(), extracted.citations().size());
        return new Attempt(new Served(cardId, card.cycle(), extracted.answer(), extracted.citations(),
                new KnowledgeMemory(cardId, card.verifiedAt(), card.expiresAt())), null);
    }

    /** ADR-0060: null when the check is off or the reranker confirms the pair; else why the card may not answer. */
    private Rejection rerankCheck(String question, String cardQuestion) {
        QaKnowledgeProperties.RerankCheck check = properties.rerankCheck();
        if (!check.enabled()) return null;
        OptionalDouble score = retrieval.relevance(question, cardQuestion);
        if (score.isEmpty()) return Rejection.RERANK_UNAVAILABLE;
        return score.getAsDouble() >= check.minScore() ? null : Rejection.RERANK_LOW;
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
