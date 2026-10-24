package com.verso.qa.worker;

import com.verso.qa.config.QaHistoryProperties;
import com.verso.qa.config.UsageMetricsProperties;
import com.verso.qa.repository.ConversationRepository;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.TurnRepository;
import com.verso.qa.repository.UsageRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * History retention (ADR-0023; KVKK storage limitation): a conversation lives {@code verso.qa.history.retention-days}
 * from its start; then its turns, citations, ratings and title are deleted, and so are the per-account activity rows of
 * days that old. Measured from the start, not the last turn: the title is the first question, and a conversation that
 * is continued must not keep it for ever. The anonymous daily counts stay.
 *
 * <p>Multi-instance (reference 11.1): every instance schedules it; each batch runs in its own short transaction under
 * {@code pg_try_advisory_xact_lock}, so only one instance deletes at a time and the others skip that round. A delete is
 * idempotent: a batch that loses a race deletes fewer rows, never wrong ones. Restoring a backup brings expired rows
 * back; the next run removes them again.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HistoryRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(HistoryRetentionJob.class);
    /** Bounds one run; what is left is deleted in the next one. */
    private static final int MAX_BATCHES_PER_KIND = 200;

    /** What one run deleted; {@code skipped} when another instance held the lock. */
    public record Result(int conversations, int turns, int activityRows, boolean skipped) {
    }

    private final QaDatabase db;
    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final UsageRepository usage;
    private final QaHistoryProperties properties;
    private final UsageMetricsProperties metricsProperties;
    private final Clock clock;

    public HistoryRetentionJob(QaDatabase db, ConversationRepository conversations, TurnRepository turns,
                               UsageRepository usage, QaHistoryProperties properties,
                               UsageMetricsProperties metricsProperties, Clock clock) {
        this.db = db;
        this.conversations = conversations;
        this.turns = turns;
        this.usage = usage;
        this.properties = properties;
        this.metricsProperties = metricsProperties;
        this.clock = clock;
    }

    /** One failure must not stop the next run (reference 23.4); a run that deleted nothing is not logged. */
    @Scheduled(fixedDelayString = "${verso.qa.history.purge-interval:PT1H}",
            initialDelayString = "${verso.qa.history.purge-initial-delay:PT5M}")
    public void poll() {
        if (!properties.purgeEnabled()) return;
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("History retention failed: exceptionType={}", e.getClass().getSimpleName());
        }
    }

    public Result runOnce() {
        Instant cutoff = clock.instant().minus(Duration.ofDays(properties.retentionDays()));
        LocalDate cutoffDay = LocalDate.ofInstant(cutoff, metricsProperties.zone());
        int batch = properties.purgeBatchSize();
        // Turns first, in bounded batches, then the emptied conversations: a conversation of 200 turns never turns into
        // one cascading statement against the role's 10 s statement timeout.
        int turnCount = deleteInBatches(() -> turns.purgeOfExpiredConversations(cutoff, batch), batch);
        if (turnCount < 0) return skipped();
        int conversationCount = deleteInBatches(() -> conversations.purgeEmpty(cutoff, batch), batch);
        if (conversationCount < 0) return skipped();
        int activityCount = deleteInBatches(() -> usage.purgeActive(cutoffDay, batch), batch);
        if (activityCount < 0) return skipped();
        if (conversationCount + turnCount + activityCount > 0) {
            log.info("History retention: conversations={} turns={} activityRows={} retentionDays={} outcome=deleted",
                    conversationCount, turnCount, activityCount, properties.retentionDays());
        }
        return new Result(conversationCount, turnCount, activityCount, false);
    }

    private Result skipped() {
        log.info("History retention: outcome=skipped reason=LOCKED_BY_ANOTHER_INSTANCE");
        return new Result(0, 0, 0, true);
    }

    /** -1 when another instance holds the lock; otherwise the rows deleted. */
    private int deleteInBatches(IntSupplier deleteBatch, int batch) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_KIND; i++) {
            Integer deleted = db.inTransaction(status -> conversations.tryLockRetention() ? deleteBatch.getAsInt() : -1);
            if (deleted < 0) return total == 0 ? -1 : total;
            total += deleted;
            if (deleted < batch) break;
        }
        return total;
    }
}
