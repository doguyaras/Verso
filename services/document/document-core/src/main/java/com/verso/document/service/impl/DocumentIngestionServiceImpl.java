package com.verso.document.service.impl;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.document.api.DocumentIngestion;
import com.verso.document.api.dto.IngestionRequest;
import com.verso.document.api.dto.IngestionResult;
import com.verso.document.api.dto.IngestionResult.Outcome;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.config.CollectionProperties;
import com.verso.document.config.DocumentProperties;
import com.verso.document.api.dto.DocumentChange;
import com.verso.document.outbox.DocumentAuditOutbox;
import com.verso.document.outbox.DocumentChangeOutbox;
import com.verso.document.repository.CollectionRepository;
import com.verso.document.repository.CollectionRepository.LockMode;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.service.DocumentFormats;
import com.verso.document.service.FileNames;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DocumentIngestion} (ADR-0038): the upload use case without a user. Same checks and the same lock order as
 * {@code DocumentServiceImpl.upload} into a collection (collection row, collection quota lock, account lock, document
 * row), the same format detection and size limit (ADR-0016), and the same insert that copies the collection's grants
 * into the reader rows (ADR-0028). A changed file's old document is deleted in the same transaction, so the collection
 * never holds both versions.
 *
 * <p>Audit (ADR-0026, ADR-0039): every added or deleted document is written to the document outbox in the transaction
 * of the change, as {@code system:connector-sync} (there is no authenticated caller). No log of the file name: ids,
 * sizes and fixed outcomes only (llm-rules 2.1).
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentIngestionServiceImpl implements DocumentIngestion {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionServiceImpl.class);
    /** Same as a user's delete: longer than the worker's longest transaction (DocumentServiceImpl). */
    private static final Duration DELETE_LOCK_WAIT = Duration.ofSeconds(15);

    private final DocumentRepository repository;
    private final CollectionRepository collections;
    private final DocumentProperties properties;
    private final CollectionProperties collectionProperties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final DocumentAuditOutbox audit;
    private final DocumentChangeOutbox changes;

    public DocumentIngestionServiceImpl(DocumentRepository repository, CollectionRepository collections,
                                        DocumentProperties properties, CollectionProperties collectionProperties,
                                        TransactionTemplate transaction, Clock clock, DocumentAuditOutbox audit,
            DocumentChangeOutbox changes) {
        this.changes = changes;
        this.repository = repository;
        this.collections = collections;
        this.properties = properties;
        this.collectionProperties = collectionProperties;
        this.transaction = transaction;
        this.clock = clock;
        this.audit = audit;
    }

    @Override
    public long maxFileBytes() {
        return properties.maxFileSize().toBytes();
    }

    @Override
    public boolean collectionExists(UUID collectionId) {
        return collections.exists(collectionId);
    }

    @Override
    public IngestionResult submit(IngestionRequest request) {
        String account = request.sourceAccount();
        Optional<UUID> earlier = repository.findIdByIdempotencyKey(account, request.idempotencyKey());
        if (earlier.isPresent()) return new IngestionResult(Outcome.REPLAYED, earlier.get());
        byte[] content = request.content();
        if (content.length == 0) return IngestionResult.rejected(Outcome.EMPTY);
        if (content.length > maxFileBytes()) return IngestionResult.rejected(Outcome.TOO_LARGE);
        Optional<DocumentFormat> format = DocumentFormats.detect(request.fileName(), content);
        if (format.isEmpty()) return IngestionResult.rejected(Outcome.UNSUPPORTED);
        String name = FileNames.sanitize(request.fileName());
        UUID collectionId = request.collectionId();
        try {
            DocumentRow created = transaction.execute(status -> {
                if (!collections.lock(collectionId, LockMode.SHARE)) throw new Refused(Outcome.COLLECTION_MISSING);
                repository.lockCollectionQuota(collectionId);
                boolean replaced = false;
                if (request.replaces() != null) {
                    // The old version first: the quota below then counts the collection as it will be after the commit.
                    repository.waitForLocksUpTo(DELETE_LOCK_WAIT);
                    replaced = repository.deleteFromCollection(collectionId, request.replaces());
                }
                if (repository.countInCollection(collectionId) >= collectionProperties.maxDocumentsPerCollection()) {
                    throw new Refused(Outcome.COLLECTION_FULL);
                }
                repository.lockAccount(account);
                if (repository.countQueued(account) >= properties.maxQueuedPerAccount()) {
                    throw new Refused(Outcome.QUEUE_FULL);
                }
                DocumentRow row = repository.insert(account, collectionId, name, format.get(), content.length,
                        request.idempotencyKey(), clock.instant());
                repository.insertFile(row.id(), content);
                if (replaced) {
                    event(AuditAction.CONNECTOR_DOCUMENT_DELETED, request.replaces(), "SOURCE_CHANGED");
                    // Verified answers citing the old version go stale (ADR-0049), in the same commit.
                    changes.append(request.replaces(), DocumentChange.Kind.REPLACED);
                }
                event(AuditAction.CONNECTOR_DOCUMENT_ADDED, row.id(), request.replaces() != null ? "SOURCE_CHANGED" : "SOURCE_NEW");
                return row;
            });
            log.info("Document ingested from a connector: documentId={} format={} sizeBytes={} replaced={} "
                            + "operation=connector-submit outcome=accepted", created.id(), created.format(),
                    created.sizeBytes(), request.replaces() != null);
            return new IngestionResult(Outcome.CREATED, created.id());
        } catch (Refused refused) {
            // Rolled back: a refused change keeps the old version of a changed file.
            return IngestionResult.rejected(refused.outcome);
        } catch (DuplicateKeyException e) {
            // A concurrent submit with the same key committed first (cannot happen under the connector's lock, but the
            // contract does not rely on it).
            return repository.findIdByIdempotencyKey(account, request.idempotencyKey())
                    .map(id -> new IngestionResult(Outcome.REPLAYED, id)).orElseThrow(() -> e);
        }
    }

    @Override
    public Set<UUID> present(UUID collectionId, Collection<UUID> documentIds) {
        if (documentIds.size() > MAX_PRESENT_BATCH) throw new IllegalArgumentException("at most " + MAX_PRESENT_BATCH + " ids");
        if (documentIds.isEmpty()) return Set.of();
        return new HashSet<>(repository.presentInCollection(collectionId, documentIds));
    }

    @Override
    public boolean delete(UUID collectionId, UUID documentId, String reason) {
        AuditEvent event = AuditEvent.success(AuditAction.CONNECTOR_DOCUMENT_DELETED, AuditTargetType.DOCUMENT, documentId)
                .withDetail(reason);
        Boolean deleted = transaction.execute(status -> {
            // A grant change in this collection waits, as for a user's delete (DocumentServiceImpl.delete).
            if (!collections.lock(collectionId, LockMode.SHARE)) return false;
            repository.waitForLocksUpTo(DELETE_LOCK_WAIT);
            boolean gone = repository.deleteFromCollection(collectionId, documentId);
            if (gone) {
                audit.appendSystem(AUDIT_COMPONENT, event);
                changes.append(documentId, DocumentChange.Kind.DELETED);
            }
            return gone;
        });
        boolean removed = Boolean.TRUE.equals(deleted);
        if (removed) log.info("Document deleted by a connector: documentId={} operation=connector-delete outcome=deleted",
                documentId);
        return removed;
    }

    /** Inside the change's transaction (the outbox writer is MANDATORY). */
    private void event(AuditAction action, UUID documentId, String detail) {
        audit.appendSystem(AUDIT_COMPONENT, AuditEvent.success(action, AuditTargetType.DOCUMENT, documentId).withDetail(detail));
    }

    /** Rolls the transaction back with a result, not an error: the caller decides what a refusal means. */
    private static final class Refused extends RuntimeException {
        private final Outcome outcome;

        Refused(Outcome outcome) {
            super(outcome.name(), null, false, false);
            this.outcome = outcome;
        }
    }
}
