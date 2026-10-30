package com.verso.document.worker;

import com.verso.document.config.ConditionalOnIngestion;
import com.verso.document.config.SystemModelProperties;
import com.verso.document.repository.SystemModelRepository;
import com.verso.document.systemmodel.SystemModelExtractor;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Extracts the system model of READY documents that have none of the running extractor version (ADR-0054): documents
 * from before the model existed, and every document after an extractor change. One document per short transaction,
 * claimed with FOR NO KEY UPDATE SKIP LOCKED on its row, so several instances share the work without a lease table and
 * nothing is extracted twice at once (reference 11.1). Incremental: a document whose model is current is never taken.
 * Runs with the ingestion worker (ADR-0039 process role).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnIngestion
public class SystemModelBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(SystemModelBackfillJob.class);

    private final SystemModelRepository repository;
    private final SystemModelIndexer indexer;
    private final SystemModelProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public SystemModelBackfillJob(SystemModelRepository repository, SystemModelIndexer indexer,
                                  SystemModelProperties properties, TransactionTemplate transaction,
                                  Clock clock) {
        this.repository = repository;
        this.indexer = indexer;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${verso.document.system-model.backfill-interval:1m}",
            initialDelayString = "${verso.document.system-model.backfill-interval:1m}")
    public void poll() {
        if (!properties.backfillEnabled()) return;
        try {
            int done = runOnce();
            if (done > 0) log.info("System model backfill ran: documents={}", done);
        } catch (RuntimeException e) {
            log.error("System model backfill failed: code=SYSTEM_MODEL_BACKFILL_FAILED exceptionType={}",
                    e.getClass().getSimpleName());
        }
    }

    /** Extracts up to one batch of pending documents; returns how many. Tests call it directly. */
    public int runOnce() {
        int done = 0;
        while (done < properties.backfillBatch()) {
            Optional<Boolean> next;
            UUID[] taken = new UUID[1];
            try {
                next = transaction.execute(status -> {
                    Optional<UUID> id = repository.lockNextPending(SystemModelExtractor.VERSION);
                    // The candidate query can run on a snapshot older than another instance's commit, so the row
                    // may already be done: the indexer re-checks under the lock and says CURRENT, which is not counted.
                    return id.map(documentId -> {
                        taken[0] = documentId;
                        return indexer.index(documentId, clock.instant()) == SystemModelIndexer.Outcome.EXTRACTED;
                    });
                });
            } catch (RuntimeException e) {
                // A database failure (connection lost, lock timeout, deadlock) is not the document's fault: recording
                // an empty model would hide its entities from the system map until the next extractor version. The
                // round ends; the next one takes the document again.
                if (taken[0] == null || e instanceof DataAccessException) throw e;
                // A document the extractor cannot handle must not block the queue: it gets an empty model of this
                // version (taken again only after an extractor change) and an ERROR line to look at.
                log.error("System model extraction failed: code=SYSTEM_MODEL_EXTRACTION_FAILED documentId={} "
                        + "exceptionType={}", taken[0], e.getClass().getSimpleName());
                transaction.executeWithoutResult(status -> indexer.markEmpty(taken[0], clock.instant()));
                next = Optional.of(true);
            }
            if (next == null || next.isEmpty()) break;
            if (next.get()) done++;
        }
        return done;
    }
}
