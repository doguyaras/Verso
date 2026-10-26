package com.verso.qa.service;

import com.verso.document.api.DocumentChangeListener;
import com.verso.document.api.dto.DocumentChange;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.api.dto.KnowledgeStaleReason;
import com.verso.qa.config.QaKnowledgeProperties;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.KnowledgeCardRepository.ServedFrom;
import com.verso.qa.repository.QaDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The answer memory's automatic transitions (ADR-0049). They only ever <b>demote</b>: a card goes STALE when a cited
 * document is deleted, replaced or moved (document outbox, {@link DocumentChangeListener}), when its text no longer
 * has the bound hashes (nightly check) or when its verification expires; it goes DISPUTED when enough distinct users
 * rated an answer served from it down. Promotion, archiving and anything about access are human (ADR-0048).
 *
 * <p>Every transition is a conditional update with its event and audit row in one qa transaction; a repeated
 * delivery finds the card already stale and changes nothing (idempotent).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeTransitions implements DocumentChangeListener {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTransitions.class);

    private final QaDatabase db;
    private final KnowledgeCardRepository cards;
    private final QaAudit audit;
    private final QaKnowledgeProperties properties;
    private final Clock clock;

    public KnowledgeTransitions(QaDatabase db, KnowledgeCardRepository cards, QaAudit audit,
                                QaKnowledgeProperties properties, Clock clock) {
        this.db = db;
        this.cards = cards;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Called by the document outbox relay after the change committed (at least once). */
    @Override
    public void documentChanged(DocumentChange change) {
        KnowledgeStaleReason reason = KnowledgeCardRules.staleReason(change.kind());
        List<UUID> citing = cards.liveCardsCiting(change.documentId());
        int stale = 0;
        for (UUID cardId : citing) {
            if (db.inTransaction(status -> markStaleInTransaction(cardId, reason))) stale++;
        }
        if (stale > 0) {
            log.info("Verified answers went stale: documentId={} change={} cards={}", change.documentId(),
                    change.kind(), stale);
        }
    }

    /** One card to STALE with its event and audit row; false when it was already stale or archived. */
    public boolean markStaleInTransaction(UUID cardId, KnowledgeStaleReason reason) {
        Instant now = clock.instant();
        Optional<KnowledgeCardState> before = cards.markStale(cardId, reason, now);
        if (before.isEmpty()) return false;
        cards.insertEvent(cardId, before.get(), KnowledgeCardState.STALE, reason.name(), now);
        audit.cardStaleInTransaction(cardId, reason.name());
        return true;
    }

    /**
     * Inside the feedback transaction (ADR-0023): a down rating of a turn answered from a card is a dispute of that card,
     * counted once per user, card and verification cycle (pseudonymous, {@link KnowledgeCardRules#disputant}). A
     * dispute of an older cycle (the card was verified again since) is not counted. Past the threshold a VERIFIED card
     * becomes DISPUTED and is no longer served.
     */
    public void downRatedInTransaction(String accountId, UUID turnId, String reason, LocalDate day) {
        Optional<ServedFrom> servedFrom = cards.servedFrom(turnId);
        if (servedFrom.isEmpty()) return;
        UUID cardId = servedFrom.get().cardId();
        Optional<KnowledgeCardRepository.CardRow> locked = cards.lock(cardId);
        if (locked.isEmpty() || locked.get().cycle() != servedFrom.get().cycle()) return;
        Instant now = clock.instant();
        if (!cards.insertDispute(cardId, locked.get().cycle(), KnowledgeCardRules.disputant(cardId, accountId),
                reason == null ? "NONE" : reason, day, now)) {
            return;
        }
        int disputes = locked.get().disputeCount() + 1;
        if (locked.get().state() == KnowledgeCardState.VERIFIED
                && KnowledgeCardRules.disputed(disputes, properties.disputeThreshold()) && cards.markDisputed(cardId, now)) {
            cards.insertEvent(cardId, KnowledgeCardState.VERIFIED, KnowledgeCardState.DISPUTED, "DISPUTE_THRESHOLD", now);
            audit.cardDisputedInTransaction(cardId);
            log.info("Verified answer disputed: cardId={} disputes={} outcome=no_longer_served", cardId, disputes);
        }
    }

    /** VERIFIED cards whose verification ran out go STALE (EXPIRED); a bounded batch per call. */
    public int expireDue(int limit) {
        Integer expired = db.inTransaction(status -> {
            int count = 0;
            for (UUID cardId : cards.expiredVerified(clock.instant(), limit)) {
                if (markStaleInTransaction(cardId, KnowledgeStaleReason.EXPIRED)) count++;
            }
            return count;
        });
        return expired == null ? 0 : expired;
    }
}
