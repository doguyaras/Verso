package com.verso.qa.service.impl;

import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.FeedbackRating;
import com.verso.qa.api.dto.FeedbackReason;
import com.verso.qa.api.dto.FeedbackRequest;
import com.verso.qa.api.dto.TurnFeedback;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.repository.FeedbackRepository;
import com.verso.qa.repository.FeedbackRepository.FeedbackRow;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.TurnRepository;
import com.verso.qa.repository.TurnRepository.OwnedTurn;
import com.verso.qa.repository.UsageRepository;
import com.verso.qa.service.FeedbackService;
import com.verso.qa.service.QaAudit;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Answer feedback (ADR-0023). Only the owner of the turn rates it; a turn of another account, or of another
 * conversation, is "not found". Rating again replaces the earlier rating, and the daily rollup moves its one count in
 * the same transaction (idempotent per turn: the same rating twice changes nothing).
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FeedbackServiceImpl implements FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackServiceImpl.class);

    private final QaDatabase db;
    private final TurnRepository turns;
    private final FeedbackRepository feedback;
    private final UsageRepository usage;
    private final Clock clock;
    private final QaAudit audit;

    public FeedbackServiceImpl(QaDatabase db, TurnRepository turns, FeedbackRepository feedback, UsageRepository usage,
                               Clock clock, QaAudit audit) {
        this.audit = audit;
        this.db = db;
        this.turns = turns;
        this.feedback = feedback;
        this.usage = usage;
        this.clock = clock;
    }

    @Override
    public TurnFeedback rate(AccountId account, UUID conversationId, UUID turnId, FeedbackRequest request) {
        String rating = request.rating().name();
        String reason = request.reason() == null ? null : request.reason().name();
        FeedbackRow stored = db.inTransaction(status -> {
            OwnedTurn turn = turns.findOwned(account.value(), conversationId, turnId)
                    .orElseThrow(() -> new QaServiceException(QaErrorCode.TURN_NOT_FOUND, "TURN_NOT_FOUND"));
            if (request.citationNumber() != null && !turns.hasCitation(turn.id(), request.citationNumber())) {
                throw new QaServiceException(QaErrorCode.FEEDBACK_CITATION_UNKNOWN, "FEEDBACK_CITATION_UNKNOWN");
            }
            feedback.lockTurn(turn.id());
            Optional<FeedbackRow> earlier = feedback.find(turn.id());
            FeedbackRow row;
            try {
                row = feedback.upsert(turn.id(), rating, reason, request.citationNumber(), clock.instant());
            } catch (DataIntegrityViolationException e) {
                // The turn was deleted between the ownership read and the write (its conversation, or retention).
                throw new QaServiceException(QaErrorCode.TURN_NOT_FOUND, "TURN_DELETED");
            }
            moveCount(turn.day(), earlier.orElse(null), rating, reason);
            // The audit record commits with the rating (ADR-0039).
            audit.feedbackGivenInTransaction(conversationId, turnId, request);
            return row;
        });
        log.info("Answer rated: turnId={} rating={} reason={} operation=feedback outcome=stored", turnId, rating,
                reason == null ? "none" : reason);
        return new TurnFeedback(FeedbackRating.valueOf(stored.rating()),
                stored.reason() == null ? null : FeedbackReason.valueOf(stored.reason()), stored.citationNumber(),
                stored.updatedAt());
    }

    /**
     * Moves one count of the day's rollup from the old rating to the new one. The two counter rows are always updated
     * in the same (key) order: two ratings of different turns moving in opposite directions would otherwise lock them
     * in opposite orders and deadlock.
     */
    private void moveCount(java.time.LocalDate day, FeedbackRow earlier, String rating, String reason) {
        String newKey = key(rating, reason);
        if (earlier != null && newKey.equals(key(earlier.rating(), earlier.reason()))) return;
        java.util.TreeMap<String, Runnable> updates = new java.util.TreeMap<>();
        updates.put(newKey, () -> usage.addFeedback(day, rating, reason, 1));
        if (earlier != null) {
            updates.put(key(earlier.rating(), earlier.reason()),
                    () -> usage.addFeedback(day, earlier.rating(), earlier.reason(), -1));
        }
        updates.values().forEach(Runnable::run);
    }

    private static String key(String rating, String reason) {
        return rating + "/" + (reason == null ? UsageRepository.NO_REASON : reason);
    }
}
