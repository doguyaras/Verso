package com.verso.connector.worker;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.domain.GlobSet;
import com.verso.connector.domain.SourcePaths;
import com.verso.connector.domain.SourceSettings;
import com.verso.connector.domain.SyncPlanner;
import com.verso.connector.domain.SyncPlanner.Fetch;
import com.verso.connector.domain.SyncPlanner.KnownItem;
import com.verso.connector.domain.SyncPlanner.Plan;
import com.verso.connector.domain.SyncPlanner.Reason;
import com.verso.connector.outbox.ConnectorAudit;
import com.verso.connector.repository.ConnectorDatabase;
import com.verso.connector.repository.ConnectorItemRepository;
import com.verso.connector.repository.ConnectorItemRepository.ItemRow;
import com.verso.connector.repository.ConnectorRepository;
import com.verso.connector.repository.ConnectorRepository.Finish;
import com.verso.connector.repository.ConnectorRow;
import com.verso.connector.repository.SyncRunRepository;
import com.verso.connector.repository.SyncRunRepository.RunRow;
import com.verso.connector.repository.SyncRunRepository.Tally;
import com.verso.connector.smb.FileSource;
import com.verso.connector.smb.FileSource.Listing;
import com.verso.connector.smb.FileSourceFactory;
import com.verso.connector.smb.SourceException;
import com.verso.document.api.DocumentIngestion;
import com.verso.document.api.dto.IngestionRequest;
import com.verso.document.api.dto.IngestionResult;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * One sync run of one claimed connector (ADR-0038): list, diff, fetch and ingest, delete, record.
 *
 * <p><b>Consistency without a saga</b> (operation-consistency 1): every step is idempotent. A file's document is stored
 * by the document module in its own transaction under a key derived from (connector, path, content hash); the item row
 * is written after it. A crash between the two leaves an item that does not know its document yet: the next run fetches
 * the file again, gets the same key and finds the stored document (REPLAYED) instead of adding a copy. A deletion
 * removes the document first, then the item; repeating it deletes nothing twice.
 *
 * <p><b>Failure isolation</b>: a file that cannot be read or ingested is recorded and retried with backoff; the run goes
 * on. Only a failure of the source itself (egress, credentials, connection, missing share or prefix, missing collection)
 * stops the run, and then nothing is deleted.
 *
 * <p><b>Privacy</b> (llm-rules 2.1): logs and audit rows carry ids, counts and fixed codes; never a path, a file name,
 * the host or the share.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SyncRunner {

    private static final Logger log = LoggerFactory.getLogger(SyncRunner.class);
    private static final int PURGE_BATCH = 100;

    private final ConnectorRepository connectors;
    private final ConnectorItemRepository items;
    private final SyncRunRepository runs;
    private final FileSourceFactory sources;
    private final DocumentIngestion documents;
    private final ConnectorProperties properties;
    private final ConnectorMetrics metrics;
    private final ConnectorAudit audit;
    private final ConnectorDatabase db;
    private final Clock clock;

    public SyncRunner(ConnectorRepository connectors, ConnectorItemRepository items, SyncRunRepository runs,
                      FileSourceFactory sources, DocumentIngestion documents, ConnectorProperties properties,
                      ConnectorMetrics metrics, ConnectorAudit audit, ConnectorDatabase db, Clock clock) {
        this.connectors = connectors;
        this.items = items;
        this.runs = runs;
        this.sources = sources;
        this.documents = documents;
        this.properties = properties;
        this.metrics = metrics;
        this.audit = audit;
        this.db = db;
        this.clock = clock;
    }

    /** Thrown when another instance took the connector over (our lease expired): stop without writing a result. */
    static final class LeaseLost extends RuntimeException {
        LeaseLost() {
            super("LEASE_LOST", null, false, false);
        }
    }

    /** Renews the lease when a third of it has passed. */
    private final class Lease {
        private final ConnectorRow connector;
        private final UUID token;
        private long renewedAt = System.nanoTime();

        Lease(ConnectorRow connector, UUID token) {
            this.connector = connector;
            this.token = token;
        }

        void keepAlive() {
            if (System.nanoTime() - renewedAt < properties.lease().toNanos() / 3) return;
            if (!connectors.renew(connector.id(), token, properties.lease())) throw new LeaseLost();
            renewedAt = System.nanoTime();
        }
    }

    /** Mutable counts of one run. */
    private static final class Counter {
        boolean listingComplete;
        int listed, added, updated, deleted, unchanged, failed, skipped, deferred;
        String problem;
        boolean consumedMassDeletion;
        boolean limited;

        void problem(String code) {
            if (problem == null) problem = code;
        }

        Tally tally() {
            return new Tally(listingComplete, listed, added, updated, deleted, unchanged, failed, skipped, deferred);
        }
    }

    /**
     * Runs the claimed connector to the end, or removes a deleted one's documents.
     *
     * @param stopping true once the application shuts down: the run stops between files and frees the lease
     */
    public void run(ConnectorRow connector, UUID token, BooleanSupplier stopping) {
        Lease lease = new Lease(connector, token);
        try {
            if (connector.deleting()) {
                purge(connector, token, lease, stopping);
            } else {
                sync(connector, token, lease, stopping);
            }
        } catch (LeaseLost e) {
            // Another instance resumed it; its claim closes our run row as ABANDONED.
            log.warn("Connector sync stopped: connectorId={} code=LEASE_LOST", connector.id());
        }
    }

    private void sync(ConnectorRow connector, UUID token, Lease lease, BooleanSupplier stopping) {
        RunRow run = db.transactionally(() -> {
            RunRow started = runs.start(connector.id());
            audit.appendSystem(AuditEvent.success(AuditAction.CONNECTOR_SYNC_STARTED, AuditTargetType.CONNECTOR,
                    connector.id()));
            return started;
        });
        Counter count = new Counter();
        String fatal = null;
        boolean interrupted = false;
        boolean backlog = false;
        try {
            SourceSettings settings = settings(connector);
            if (!documents.collectionExists(connector.collectionId())) throw SourceException.fatal("COLLECTION_MISSING");
            try (FileSource source = sources.open(settings)) {
                Listing listing = source.list(lease::keepAlive);
                count.listingComplete = listing.complete();
                count.listed = listing.files().size();
                if (!listing.complete()) count.problem(listing.problem());
                Map<String, KnownItem> known = known(connector.id());
                Set<UUID> missing = missingDocuments(connector.collectionId(), known.values());
                Instant now = clock.instant();
                Plan plan = SyncPlanner.plan(listing.files(), known, missing, listing.complete(), now);
                count.unchanged = plan.unchanged();
                count.deferred = plan.waiting();
                int files = 0;
                long bytes = 0;
                for (Fetch fetch : plan.fetch()) {
                    if (stopping.getAsBoolean()) {
                        interrupted = true;
                        count.deferred++;
                        continue;
                    }
                    if (backlog || files >= properties.maxFilesPerRun() || bytes >= properties.maxBytesPerRun().toBytes()) {
                        count.deferred++;
                        count.limited = true;
                        count.problem(backlog ? "BACKLOG" : "RUN_LIMIT");
                        continue;
                    }
                    lease.keepAlive();
                    files++;
                    Outcome outcome = fetch(source, settings, connector, fetch, count);
                    bytes += outcome.bytes;
                    if (outcome.backlog) backlog = true;
                }
                if (listing.complete() && !interrupted) {
                    deletions(connector, plan.delete(), known.size(), count, lease);
                }
            }
        } catch (SourceException e) {
            if (!e.isFatal()) throw new IllegalStateException("non-fatal source failure escaped", e);
            fatal = e.code();
            log.warn("Connector sync failed: connectorId={} runId={} code={} exceptionType={}", connector.id(), run.id(),
                    e.code(), e.causeType());
        } catch (IllegalArgumentException e) {
            // A stored setting the current rules refuse (a glob, a prefix): fixed by the admin, never retried blindly.
            fatal = "CONFIG_INVALID";
            log.warn("Connector sync failed: connectorId={} runId={} code=CONFIG_INVALID", connector.id(), run.id());
        } catch (LeaseLost e) {
            throw e;
        } catch (RuntimeException e) {
            fatal = "SYNC_ERROR";
            log.error("Connector sync failed: connectorId={} runId={} code=SYNC_ERROR exceptionType={}", connector.id(),
                    run.id(), e.getClass().getSimpleName());
        }
        finish(connector, token, run, count, fatal, interrupted, backlog);
    }

    /** What fetching one file did. */
    private record Outcome(long bytes, boolean backlog) {
        static final Outcome NONE = new Outcome(0, false);
    }

    private Outcome fetch(FileSource source, SourceSettings settings, ConnectorRow connector, Fetch fetch, Counter count) {
        KnownItem known = fetch.known();
        var file = fetch.file();
        Instant now = clock.instant();
        UUID previous = known == null || fetch.reason() == Reason.RESTORE ? null : known.documentId();
        if (file.sizeBytes() > settings.maxFileBytes()) {
            skip(connector, file, previous, "TOO_LARGE", count, now);
            return Outcome.NONE;
        }
        byte[] content;
        try {
            content = source.read(file.path(), settings.maxFileBytes());
        } catch (SourceException e) {
            if (e.isFatal()) throw e;
            if (e.code().equals("TOO_LARGE") || e.code().equals("UNSAFE_LINK")) {
                skip(connector, file, previous, e.code(), count, now);
            } else {
                fail(connector, file, known, e.code(), count, now);
            }
            return Outcome.NONE;
        }
        String sha = sha256(content);
        if (previous != null && sha.equals(known.contentSha256())) {
            // Touched but identical: no new document, just the new fingerprint.
            items.synced(connector.id(), file.path(), file.fileId(), file.sizeBytes(), file.modifiedAt(), sha, previous, now);
            count.unchanged++;
            return new Outcome(content.length, false);
        }
        IngestionResult result = documents.submit(new IngestionRequest(IngestionRequest.accountOf(connector.id()),
                connector.collectionId(), SourcePaths.fileName(file.path()), content,
                SourceSettings.contentKey(connector.id(), file.path(), sha), previous));
        switch (result.outcome()) {
            case CREATED, REPLAYED -> {
                items.synced(connector.id(), file.path(), file.fileId(), file.sizeBytes(), file.modifiedAt(), sha,
                        result.documentId(), now);
                if (previous != null) {
                    count.updated++;
                    metrics.document("updated");
                } else {
                    count.added++;
                    metrics.document("added");
                }
            }
            case EMPTY, TOO_LARGE, UNSUPPORTED -> skip(connector, file, previous, result.outcome().name(), count, now);
            case COLLECTION_FULL -> fail(connector, file, known, "COLLECTION_FULL", count, now);
            case QUEUE_FULL -> {
                // Backpressure: the item is left as it was, so the next run sees the same change again.
                count.deferred++;
                metrics.itemFailed("QUEUE_FULL");
                return new Outcome(content.length, true);
            }
            case COLLECTION_MISSING -> throw SourceException.fatal("COLLECTION_MISSING");
            default -> throw new IllegalStateException("unknown outcome");
        }
        return new Outcome(content.length, false);
    }

    /**
     * Not ingestible as it is now. The previous version's document goes: the source no longer holds that content, and a
     * stale copy answering questions would be worse than none.
     */
    private void skip(ConnectorRow connector, SyncPlanner.ListedFile file, UUID previous, String code, Counter count,
                      Instant now) {
        if (previous != null && documents.delete(connector.collectionId(), previous, "SOURCE_UNREADABLE")) {
            metrics.document("deleted");
        }
        items.skipped(connector.id(), file.path(), file.fileId(), file.sizeBytes(), file.modifiedAt(), code, now);
        count.skipped++;
        metrics.itemFailed(code);
    }

    /** A failure that may pass: the last good version stays, the file is retried after a backoff. */
    private void fail(ConnectorRow connector, SyncPlanner.ListedFile file, KnownItem known, String code, Counter count,
                      Instant now) {
        int attempts = known != null && known.state() == KnownItem.State.FAILED ? known.attempts() + 1 : 1;
        Duration wait = SyncPlanner.backoff(attempts, properties.itemRetryBase(), properties.itemRetryMax());
        items.failed(connector.id(), file.path(), file.fileId(), file.sizeBytes(), file.modifiedAt(),
                known == null ? null : known.contentSha256(), known == null ? null : known.documentId(), code, attempts,
                now.plus(wait), now);
        count.failed++;
        count.problem("FILES_FAILED");
        metrics.itemFailed(code);
    }

    private void deletions(ConnectorRow connector, List<KnownItem> gone, int tracked, Counter count, Lease lease) {
        if (gone.isEmpty()) return;
        boolean allowed = SyncPlanner.deletionAllowed(gone.size(), tracked, properties.deletionSafetyPercent(),
                properties.deletionSafetyMinimum(), connector.massDeletionConfirmed());
        if (!allowed) {
            count.deferred += gone.size();
            count.problem("DELETION_SAFETY");
            log.warn("Connector deletions held back: connectorId={} code=DELETION_SAFETY deletions={} tracked={}",
                    connector.id(), gone.size(), tracked);
            return;
        }
        count.consumedMassDeletion = connector.massDeletionConfirmed();
        for (KnownItem item : gone) {
            lease.keepAlive();
            if (item.documentId() != null
                    && documents.delete(connector.collectionId(), item.documentId(), "SOURCE_DELETED")) {
                metrics.document("deleted");
            }
            items.delete(connector.id(), item.path());
            count.deleted++;
        }
    }

    private void finish(ConnectorRow connector, UUID token, RunRow run, Counter count, String fatal, boolean interrupted,
                        boolean backlog) {
        String status;
        // Success means the index caught up with the whole source: a cut or partial listing is not one (deletions
        // could not propagate), an interruption is neither success nor failure.
        Finish.Result result = interrupted ? Finish.Result.NEUTRAL
                : fatal == null && count.listingComplete ? Finish.Result.SUCCESS : Finish.Result.FAILURE;
        if (fatal != null) {
            status = "FAILED";
        } else if (interrupted) {
            status = "PARTIAL";
            count.problem("INTERRUPTED");
        } else if (!count.listingComplete || count.failed > 0 || count.deferred > 0) {
            status = "PARTIAL";
        } else {
            status = "SUCCEEDED";
        }
        String errorCode = fatal != null ? fatal : "SUCCEEDED".equals(status) ? null : count.problem;
        Duration next = nextRunIn(connector, fatal != null, interrupted, backlog || count.limited);
        // Run row, connector result and audit record commit together (outbox, ADR-0039); only the claim holder writes.
        boolean stored = db.transactionally(() -> {
            runs.finish(run.id(), status, errorCode, count.tally());
            boolean held = connectors.finish(connector.id(), token, run.startedAt(),
                    new Finish(result, errorCode, next, count.consumedMassDeletion));
            if (held) {
                audit.appendSystem(AuditEvent.success(AuditAction.CONNECTOR_SYNC_FINISHED, AuditTargetType.CONNECTOR,
                        connector.id()).withDetail(status));
            }
            return held;
        });
        if (!stored) log.warn("Connector sync result not stored: connectorId={} code=LEASE_LOST", connector.id());
        runs.trim(connector.id(), properties.keepRuns());
        metrics.run(status);
        log.info("Connector sync finished: connectorId={} runId={} status={} code={} listed={} added={} updated={} "
                        + "deleted={} unchanged={} failed={} skipped={} deferred={} listingComplete={}",
                connector.id(), run.id(), status, errorCode, count.listed, count.added, count.updated, count.deleted,
                count.unchanged, count.failed, count.skipped, count.deferred, count.listingComplete);
    }

    private Duration nextRunIn(ConnectorRow connector, boolean failed, boolean interrupted, boolean moreWork) {
        Duration schedule = Duration.ofMinutes(connector.scheduleMinutes());
        if (interrupted) return Duration.ZERO;
        if (failed) {
            Duration wait = SyncPlanner.backoff(connector.consecutiveFailures() + 1, properties.failureRetryBase(), schedule);
            return wait.compareTo(schedule) > 0 ? schedule : wait;
        }
        if (moreWork) return properties.continuationDelay().compareTo(schedule) < 0 ? properties.continuationDelay() : schedule;
        return schedule;
    }

    /**
     * A deleted connector: every document it added is deleted with its derived data (ADR-0034), then the connector row
     * with its items and runs. Repeatable: a purge cut short resumes at the next claim.
     */
    private void purge(ConnectorRow connector, UUID token, Lease lease, BooleanSupplier stopping) {
        int removed = 0;
        try {
            while (!stopping.getAsBoolean()) {
                List<ItemRow> batch = items.withDocument(connector.id(), PURGE_BATCH);
                if (batch.isEmpty()) break;
                for (ItemRow item : batch) {
                    lease.keepAlive();
                    if (documents.delete(connector.collectionId(), item.documentId(), "CONNECTOR_DELETED")) {
                        metrics.document("deleted");
                    }
                    items.delete(connector.id(), item.path());
                    removed++;
                }
            }
            if (stopping.getAsBoolean()) {
                connectors.release(connector.id(), token);
                return;
            }
            connectors.delete(connector.id(), token);
            log.info("Connector removed: connectorId={} documentsDeleted={} outcome=deleted", connector.id(), removed);
        } catch (LeaseLost e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("Connector removal failed: connectorId={} code=PURGE_FAILED documentsDeleted={} exceptionType={}",
                    connector.id(), removed, e.getClass().getSimpleName());
            connectors.finish(connector.id(), token, clock.instant(),
                    new Finish(Finish.Result.FAILURE, "PURGE_FAILED", properties.failureRetryBase(), false));
        }
    }

    /** Settings as stored; rules re-checked (a row written by an older version may not pass today's). */
    static SourceSettings settings(ConnectorRow c) {
        return new SourceSettings(c.id(), c.host(), c.port(), c.share(), SourcePaths.normalizePrefix(c.pathPrefix()),
                c.domain(), c.username(), c.credentialRef(), GlobSet.compile(c.includeGlobs()),
                GlobSet.compile(c.excludeGlobs()), c.maxFileBytes());
    }

    private Map<String, KnownItem> known(UUID connectorId) {
        Map<String, KnownItem> known = new HashMap<>();
        for (ItemRow row : items.all(connectorId)) {
            known.put(row.path(), new KnownItem(row.path(), row.fileId(), row.sizeBytes(), row.modifiedAt(),
                    row.contentSha256(), row.documentId(), KnownItem.State.valueOf(row.state()), row.nextAttemptAt(),
                    row.attempts()));
        }
        return known;
    }

    /** Synced documents that are no longer in the collection (deleted or moved in Verso). */
    private Set<UUID> missingDocuments(UUID collectionId, java.util.Collection<KnownItem> known) {
        List<UUID> ids = new ArrayList<>();
        for (KnownItem item : known) {
            if (item.state() == KnownItem.State.SYNCED && item.documentId() != null) ids.add(item.documentId());
        }
        Set<UUID> missing = new HashSet<>();
        for (int i = 0; i < ids.size(); i += DocumentIngestion.MAX_PRESENT_BATCH) {
            List<UUID> batch = ids.subList(i, Math.min(ids.size(), i + DocumentIngestion.MAX_PRESENT_BATCH));
            Set<UUID> present = documents.present(collectionId, batch);
            for (UUID id : batch) if (!present.contains(id)) missing.add(id);
        }
        return missing;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
