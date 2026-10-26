package com.verso.qa.service.impl;

import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.DocumentAccess;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.QaSubjects;
import com.verso.qa.api.dto.CitationUnit;
import com.verso.qa.api.dto.HistoryCitation;
import com.verso.qa.api.dto.KnowledgeCardRequest;
import com.verso.qa.api.dto.KnowledgeCardResponse;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.api.dto.KnowledgeCardSummary;
import com.verso.qa.config.QaKnowledgeProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.KnowledgeCardRepository.CardRow;
import com.verso.qa.repository.KnowledgeCardRepository.CitationRow;
import com.verso.qa.repository.KnowledgeCardRepository.NewCard;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.TurnRepository;
import com.verso.qa.repository.TurnRepository.OwnedAnswer;
import com.verso.qa.service.KnowledgeCardRules;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import com.verso.qa.service.KnowledgeCardRules.BindingState;
import com.verso.qa.service.KnowledgeCardRules.Scope;
import com.verso.qa.service.KnowledgeCardRules.VerifyStep;
import com.verso.qa.service.KnowledgeCardService;
import com.verso.qa.service.QaAudit;
import com.verso.qa.service.QuestionNormalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Verified answer cards (ADR-0049). Every check that reads documents runs with the admin's own principals (ADR-0028):
 * an admin can only approve an answer whose every source they can read now. Remote work (the question embedding) and
 * document reads happen before the qa transaction, which then writes the card, its citations, its hash binding, its
 * event and its audit row together (ADR-0039). Logs carry ids, never the question or the answer (llm-rules 2.1).
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeCardServiceImpl implements KnowledgeCardService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeCardServiceImpl.class);

    private final QaDatabase db;
    private final TurnRepository turns;
    private final KnowledgeCardRepository cards;
    private final DocumentProvenance provenance;
    private final DocumentRetrieval retrieval;
    private final DocumentCatalog catalog;
    private final CallerGroups groups;
    private final QaAudit audit;
    private final QaKnowledgeProperties properties;
    private final Clock clock;

    public KnowledgeCardServiceImpl(QaDatabase db, TurnRepository turns, KnowledgeCardRepository cards,
                                    DocumentProvenance provenance, DocumentRetrieval retrieval, DocumentCatalog catalog,
                                    CallerGroups groups, QaAudit audit, QaKnowledgeProperties properties, Clock clock) {
        this.db = db;
        this.turns = turns;
        this.cards = cards;
        this.provenance = provenance;
        this.retrieval = retrieval;
        this.catalog = catalog;
        this.groups = groups;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public KnowledgeCardResponse create(AccountId admin, KnowledgeCardRequest request) {
        // The admin's own answer only: another user's question and answer stay theirs (ADR-0023, works councils).
        OwnedAnswer turn = turns.findOwnedAnswer(admin.value(), request.conversationId(), request.turnId())
                .orElseThrow(() -> new QaServiceException(QaErrorCode.TURN_NOT_FOUND, "TURN_NOT_FOUND"));
        List<TurnRepository.CitationRow> cited = turns.citationsOf(turn.id());
        if (!"ANSWERED".equals(turn.outcome()) || cited.isEmpty() || QaSubjects.REDACTED_ANSWER.equals(turn.answer())) {
            throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_NOT_ANSWERED, "KNOWLEDGE_CARD_NOT_ANSWERED");
        }
        Set<SourcePage> pages = new LinkedHashSet<>();
        cited.forEach(c -> pages.add(new SourcePage(c.documentId(), c.page())));
        if (pages.size() > DocumentProvenance.MAX_PAGES) {
            throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_SOURCE_UNAVAILABLE, "TOO_MANY_SOURCES");
        }
        Reader reader = readerOf(admin);
        List<SourceChunk> chunks = provenance.sourceChunks(reader, pages);
        Scope scope = KnowledgeCardRules.scope(pages, chunks);
        if (scope.refusal().isPresent()) {
            QaErrorCode code = scope.refusal().get() == KnowledgeCardRules.ScopeProblem.PERSONAL_SOURCE
                    ? QaErrorCode.KNOWLEDGE_CARD_PERSONAL_SOURCE : QaErrorCode.KNOWLEDGE_CARD_SOURCE_UNAVAILABLE;
            throw new QaServiceException(code, scope.refusal().get().name());
        }
        String question = request.question() != null ? request.question().strip() : turn.question();
        if (question.isEmpty()) question = turn.question();
        // The one model call of this request (the local embedding model), outside any transaction (llm-rules 5.1).
        QuestionEmbedding embedding = retrieval.embed(question);
        List<String> keyFacts = request.keyFacts() == null ? List.of()
                : request.keyFacts().stream().map(String::strip).toList();
        Instant now = clock.instant();
        NewCard card = new NewCard(question, QuestionNormalizer.hash(question), embedding.vector(), embedding.model(),
                turn.answer(), keyFacts, scope.collections(), turn.id(), admin.value(), properties.requiredVerifiers(), now);
        UUID cardId;
        try {
            cardId = db.inTransaction(status -> {
                UUID id = cards.insert(card);
                for (TurnRepository.CitationRow c : cited) {
                    cards.insertCitation(id, new CitationRow(c.number(), c.documentId(), c.page(), c.unit()));
                }
                for (SourceChunk chunk : chunks) {
                    cards.insertBinding(id, new Binding(chunk.chunkId(), chunk.page(), chunk.contentSha256()));
                }
                cards.insertEvent(id, null, KnowledgeCardState.DRAFT, "CREATED", now);
                audit.cardCreatedInTransaction(id);
                if (request.verify()) verifyInTransaction(admin, id, now);
                return id;
            });
        } catch (DuplicateKeyException e) {
            throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_DUPLICATE, "KNOWLEDGE_CARD_DUPLICATE");
        }
        log.info("Verified answer card created: cardId={} turnId={} citations={} chunks={} verify={} outcome=created",
                cardId, turn.id(), cited.size(), chunks.size(), request.verify());
        return get(admin, cardId);
    }

    @Override
    public PageResponse<KnowledgeCardSummary> list(AccountId admin, KnowledgeCardState state, int page, int size) {
        List<CardRow> found = cards.list(state, page, size);
        Map<UUID, Set<UUID>> cited = cards.citedDocuments(found.stream().map(CardRow::id).toList());
        Set<UUID> readable = readableDocuments(readerOf(admin), cited.values().stream().flatMap(Set::stream).toList());
        List<KnowledgeCardSummary> rows = found.stream().map(card -> {
            boolean visible = readable.containsAll(cited.getOrDefault(card.id(), Set.of()));
            return new KnowledgeCardSummary(card.id(), visible ? card.question() : null, card.state(), card.staleReason(),
                    cards.countVerifiers(card.id(), card.cycle()), card.requiredVerifiers(), card.disputeCount(),
                    card.verifiedAt(), card.expiresAt(), card.createdAt(), card.updatedAt(), visible);
        }).toList();
        return PageResponse.of(rows, page, size, cards.count(state));
    }

    @Override
    public KnowledgeCardResponse get(AccountId admin, UUID cardId) {
        CardRow card = cards.find(cardId).orElseThrow(KnowledgeCardServiceImpl::notFound);
        List<CitationRow> citations = cards.citations(cardId);
        Map<UUID, DocumentAccess> access = catalog.access(readerOf(admin),
                citations.stream().map(CitationRow::documentId).distinct().toList());
        // ADR-0028: the text of a card is document content; an admin without a grant on every source sees none of it.
        boolean visible = citations.stream().allMatch(c -> access.containsKey(c.documentId())
                && access.get(c.documentId()).accessible());
        List<HistoryCitation> resolved = citations.stream().map(c -> {
            DocumentAccess a = access.get(c.documentId());
            boolean deleted = a == null;
            boolean accessible = a != null && a.accessible();
            return new HistoryCitation(c.number(), c.documentId(), accessible ? a.fileName() : null, c.page(),
                    CitationUnit.valueOf(c.unit()), deleted, accessible, accessible ? a.collectionId() : null,
                    accessible ? a.collectionName() : null);
        }).toList();
        List<KnowledgeCardResponse.DisputeDay> days = cards.disputesByDay(cardId, card.cycle()).stream()
                .map(r -> new KnowledgeCardResponse.DisputeDay((LocalDate) r[0], (Integer) r[1])).toList();
        List<KnowledgeCardResponse.Event> events = cards.events(cardId).stream()
                .map(r -> new KnowledgeCardResponse.Event(r[0] == null ? null : KnowledgeCardState.valueOf((String) r[0]),
                        KnowledgeCardState.valueOf((String) r[1]), (String) r[2], (Instant) r[3])).toList();
        return new KnowledgeCardResponse(card.id(), visible ? card.question() : null, visible ? card.answer() : null,
                card.state(), card.staleReason(), visible ? card.keyFacts() : List.of(), resolved, cards.countVerifiers(cardId, card.cycle()), card.requiredVerifiers(),
                admin.value().equals(card.createdBy()), cards.verifiedBy(cardId, card.cycle(), admin.value()),
                card.disputeCount(), days, events, card.verifiedAt(), card.expiresAt(), card.createdAt(),
                card.updatedAt(), visible);
    }

    /** The documents among {@code ids} this reader can read now (one statement). */
    private Set<UUID> readableDocuments(Reader reader, List<UUID> ids) {
        if (ids.isEmpty()) return Set.of();
        Set<UUID> readable = new java.util.HashSet<>();
        catalog.access(reader, ids.stream().distinct().toList()).forEach((id, a) -> {
            if (a.accessible()) readable.add(id);
        });
        return readable;
    }

    @Override
    public KnowledgeCardResponse verify(AccountId admin, UUID cardId) {
        CardRow card = cards.find(cardId).orElseThrow(KnowledgeCardServiceImpl::notFound);
        // The verifier must read every source now, and the bound text must still be there (outside the transaction).
        List<Binding> bindings = cards.bindings(cardId);
        Set<SourcePage> pages = new LinkedHashSet<>();
        bindings.forEach(b -> pages.add(b.page()));
        Reader reader = readerOf(admin);
        Map<UUID, DocumentAccess> access = catalog.access(reader, pages.stream().map(SourcePage::documentId).distinct().toList());
        boolean readable = pages.stream().allMatch(p -> access.containsKey(p.documentId())
                && access.get(p.documentId()).accessible());
        if (!readable) {
            throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_SOURCE_UNAVAILABLE, "SOURCE_NOT_READABLE");
        }
        if (KnowledgeCardRules.check(bindings, provenance.sourceChunks(reader, pages)) != BindingState.INTACT) {
            throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_SOURCE_CHANGED, "KNOWLEDGE_CARD_SOURCE_CHANGED");
        }
        if (KnowledgeCardRules.verifyStep(card.state(), card.staleReason()) == VerifyStep.REFUSED) {
            throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_STATE_CONFLICT, "STATE_" + card.state().name());
        }
        Instant now = clock.instant();
        db.inTransaction(status -> {
            verifyInTransaction(admin, cardId, now);
            return null;
        });
        return get(admin, cardId);
    }

    /** Under the card's row lock: the step of its state, the verification, and VERIFIED when the cycle has enough. */
    private void verifyInTransaction(AccountId admin, UUID cardId, Instant now) {
        CardRow card = cards.lock(cardId).orElseThrow(KnowledgeCardServiceImpl::notFound);
        VerifyStep step = KnowledgeCardRules.verifyStep(card.state(), card.staleReason());
        if (step == VerifyStep.REFUSED) {
            throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_STATE_CONFLICT, "STATE_" + card.state().name());
        }
        int cycle = card.cycle();
        if (step == VerifyStep.NEW_CYCLE) {
            if (!cards.startCycle(cardId, card.state(), now)) {
                throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_STATE_CONFLICT, "STATE_CHANGED");
            }
            cards.insertEvent(cardId, card.state(), KnowledgeCardState.DRAFT, "NEW_CYCLE", now);
            cycle++;
        }
        if (!cards.insertVerification(cardId, cycle, admin.value(), now)) {
            throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_ALREADY_VERIFIED_BY_YOU, "ALREADY_VERIFIED_BY_YOU");
        }
        int verifiers = cards.countVerifiers(cardId, cycle);
        if (KnowledgeCardRules.afterVerification(verifiers, card.requiredVerifiers()) == KnowledgeCardState.VERIFIED) {
            if (!cards.markVerified(cardId, now, now.plus(properties.verificationCadence()))) {
                throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_STATE_CONFLICT, "STATE_CHANGED");
            }
            cards.insertEvent(cardId, KnowledgeCardState.DRAFT, KnowledgeCardState.VERIFIED, "VERIFIED", now);
            // The per-customer golden set (ADR-0049): every verified card is a regression case from now on.
            cards.exportGolden(cardId, now);
            audit.cardVerifiedInTransaction(cardId, "VERIFIED");
            log.info("Verified answer card verified: cardId={} cycle={} verifiers={} outcome=verified", cardId, cycle,
                    verifiers);
        } else {
            cards.insertEvent(cardId, KnowledgeCardState.DRAFT, KnowledgeCardState.DRAFT, "VERIFICATION_ADDED", now);
            audit.cardVerifiedInTransaction(cardId, "VERIFICATION_ADDED");
            log.info("Verified answer card verification added: cardId={} cycle={} verifiers={} required={}", cardId,
                    cycle, verifiers, card.requiredVerifiers());
        }
    }

    @Override
    public KnowledgeCardResponse archive(AccountId admin, UUID cardId) {
        Instant now = clock.instant();
        db.inTransaction(status -> {
            KnowledgeCardState before = cards.archive(cardId, now).orElseGet(() -> {
                cards.find(cardId).orElseThrow(KnowledgeCardServiceImpl::notFound);
                throw new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_STATE_CONFLICT, "STATE_ARCHIVED");
            });
            cards.retireGolden(cardId, now);
            cards.insertEvent(cardId, before, KnowledgeCardState.ARCHIVED, "ARCHIVED", now);
            audit.cardArchivedInTransaction(cardId);
            return null;
        });
        log.info("Verified answer card archived: cardId={} outcome=archived", cardId);
        return get(admin, cardId);
    }

    private Reader readerOf(AccountId account) {
        return new Reader(account.value(), groups.current());
    }

    private static QaServiceException notFound() {
        return new QaServiceException(QaErrorCode.KNOWLEDGE_CARD_NOT_FOUND, "KNOWLEDGE_CARD_NOT_FOUND");
    }
}
