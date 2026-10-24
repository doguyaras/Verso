package com.verso.document.service.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.document.api.dto.DocumentResponse;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.config.CollectionProperties;
import com.verso.document.config.DocumentProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.CollectionRepository;
import com.verso.document.repository.CollectionRepository.CollectionRow;
import com.verso.document.repository.CollectionRepository.LockMode;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.service.Caller;
import com.verso.document.service.DocumentFormats;
import com.verso.document.service.DocumentMapper;
import com.verso.document.service.DocumentService;
import com.verso.document.service.FileNames;
import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.verso.document.outbox.DocumentAuditOutbox;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * /v1/documents use cases (ADR-0011, ADR-0028). Upload is a database write only: no parsing and no model call on the
 * request path (repo-context section 3: zero remote calls, 2 s budget); the worker does the rest. Logs: ids, sizes and
 * fixed outcomes, never the file name, a collection name or a group (llm-rules 2.1).
 *
 * <p>Access (ADR-0028): what a caller reads is decided by its principals in the query; verso-admin additionally manages
 * collection documents (list, get, delete, move) without reading them through Q&amp;A. Lock order, everywhere: collection
 * rows (sorted by id), then the account or collection quota lock, then the document row. A grant change takes the
 * collection row FOR UPDATE, so it never interleaves with an upload, move or delete in that collection.
 */
@Service
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);
    /** Longer than the worker's longest transaction (storing the chunks of a document at the text limit, ~6 s). */
    private static final Duration DELETE_LOCK_WAIT = Duration.ofSeconds(15);

    private final DocumentRepository repository;
    private final CollectionRepository collections;
    private final DocumentProperties properties;
    private final CollectionProperties collectionProperties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final DocumentAuditOutbox audit;

    public DocumentServiceImpl(DocumentRepository repository, CollectionRepository collections,
                               DocumentProperties properties, CollectionProperties collectionProperties,
                               TransactionTemplate transaction, Clock clock, DocumentAuditOutbox audit) {
        this.repository = repository;
        this.collections = collections;
        this.properties = properties;
        this.collectionProperties = collectionProperties;
        this.transaction = transaction;
        this.clock = clock;
        this.audit = audit;
    }

    @Override
    public DocumentResponse upload(Caller caller, UUID collectionId, String fileName, byte[] content, UUID idempotencyKey) {
        // The controller already requires verso-admin for a collection upload; this keeps the rule in the use case too.
        if (collectionId != null && !caller.admin()) {
            throw new ServiceException(CommonErrorCode.ACCESS_DENIED, "COLLECTION_UPLOAD_NOT_ADMIN");
        }
        String account = caller.account().value();
        if (idempotencyKey != null) {
            Optional<DocumentRow> earlier = repository.findByIdempotencyKey(account, idempotencyKey, caller.principals(),
                    caller.admin());
            if (earlier.isPresent()) return replayed(earlier.get());
        }
        if (content.length == 0) throw rejected(DocumentErrorCode.DOCUMENT_EMPTY);
        if (content.length > properties.maxFileSize().toBytes()) {
            // The multipart limit (same value) normally answers first; one code for one rule (review P2).
            throw new ServiceException(CommonErrorCode.PAYLOAD_TOO_LARGE, "DOCUMENT_TOO_LARGE");
        }
        DocumentFormat format = DocumentFormats.detect(fileName, content)
                .orElseThrow(() -> rejected(DocumentErrorCode.DOCUMENT_TYPE_UNSUPPORTED));
        String name = FileNames.sanitize(fileName);
        DocumentRow created;
        try {
            created = transaction.execute(status -> {
                if (collectionId != null) {
                    // Shared: no grant change in between, so the new document gets exactly the current grants.
                    if (!collections.lock(collectionId, LockMode.SHARE)) throw rejected(DocumentErrorCode.COLLECTION_NOT_FOUND);
                    repository.lockCollectionQuota(collectionId);
                    if (repository.countInCollection(collectionId) >= collectionProperties.maxDocumentsPerCollection()) {
                        throw rejected(DocumentErrorCode.COLLECTION_DOCUMENT_LIMIT_REACHED);
                    }
                }
                // Count and insert under a per-account lock: parallel uploads cannot overrun the quota (review C6).
                repository.lockAccount(account);
                if (collectionId == null && repository.count(account) >= properties.maxDocumentsPerAccount()) {
                    throw rejected(DocumentErrorCode.DOCUMENT_LIMIT_REACHED);
                }
                if (repository.countQueued(account) >= properties.maxQueuedPerAccount()) {
                    throw rejected(DocumentErrorCode.DOCUMENT_QUEUE_FULL);
                }
                DocumentRow row = repository.insert(account, collectionId, name, format, content.length, idempotencyKey,
                        clock.instant());
                repository.insertFile(row.id(), content);
                // Same transaction (ADR-0039): never for a replay, never lost after the commit.
                audit.append(AuditEvent.success(AuditAction.DOCUMENT_UPLOADED, AuditTargetType.DOCUMENT, row.id()));
                return row;
            });
        } catch (DuplicateKeyException e) {
            // Two uploads with the same key at once: the first one won (reference 6.4).
            // The earlier document may have moved out of the caller's reach: then the key is spent, nothing is described.
            return replayed(repository.findByIdempotencyKey(account, idempotencyKey, caller.principals(),
                    caller.admin())
                    .orElseThrow(() -> idempotencyKey == null ? e : rejected(DocumentErrorCode.IDEMPOTENCY_KEY_REUSED)));
        }
        log.info("Document upload accepted: documentId={} format={} sizeBytes={} space={} operation=upload outcome=accepted",
                created.id(), created.format(), created.sizeBytes(), created.personal() ? "personal" : "collection");
        return DocumentMapper.toResponse(created);
    }

    @Override
    public PageResponse<DocumentResponse> list(Caller caller, UUID collectionId, int page, int size) {
        if (collectionId == null) {
            String[] principals = caller.principals();
            List<DocumentResponse> rows = repository.listReadable(principals, page, size).stream()
                    .map(DocumentMapper::toResponse).toList();
            return PageResponse.of(rows, page, size, repository.countReadable(principals));
        }
        visibleCollection(caller, collectionId);
        if (caller.admin()) {
            // verso-admin manages every collection, readable or not (ADR-0028).
            List<DocumentResponse> rows = repository.listInCollection(collectionId, page, size).stream()
                    .map(DocumentMapper::toResponse).toList();
            return PageResponse.of(rows, page, size, repository.countInCollection(collectionId));
        }
        // Second layer behind visibleCollection: the documents themselves are filtered by the reader's principals.
        String[] principals = caller.principals();
        List<DocumentResponse> rows = repository.listReadableInCollection(principals, collectionId, page, size).stream()
                .map(DocumentMapper::toResponse).toList();
        return PageResponse.of(rows, page, size, repository.countReadableInCollection(principals, collectionId));
    }

    @Override
    public DocumentResponse get(Caller caller, UUID documentId) {
        return visible(caller, documentId).map(DocumentMapper::toResponse)
                .orElseThrow(() -> rejected(DocumentErrorCode.DOCUMENT_NOT_FOUND));
    }

    @Override
    public void delete(Caller caller, UUID documentId) {
        String account = caller.account().value();
        Boolean deleted = transaction.execute(status -> {
            repository.waitForLocksUpTo(DELETE_LOCK_WAIT);
            boolean gone = repository.delete(account, documentId);
            if (gone) audit.append(AuditEvent.success(AuditAction.DOCUMENT_DELETED, AuditTargetType.DOCUMENT, documentId));
            return gone;
        });
        if (!Boolean.TRUE.equals(deleted)) {
            // Not a personal document of the caller: a collection document is verso-admin's to delete (ADR-0028).
            DocumentRow document = visible(caller, documentId).filter(row -> !row.personal())
                    .orElseThrow(() -> rejected(DocumentErrorCode.DOCUMENT_NOT_FOUND));
            if (!caller.admin()) throw rejected(DocumentErrorCode.DOCUMENT_MANAGED_BY_ADMIN);
            UUID collectionId = document.collectionId();
            Boolean removed = transaction.execute(status -> {
                // A grant change in this collection waits: it would otherwise add a principal to a vanishing row.
                if (!collections.lock(collectionId, LockMode.SHARE)) return false;
                repository.waitForLocksUpTo(DELETE_LOCK_WAIT);
                boolean gone = repository.deleteFromCollection(collectionId, documentId);
                if (gone) {
                    audit.append(AuditEvent.success(AuditAction.DOCUMENT_DELETED, AuditTargetType.DOCUMENT, documentId));
                }
                return gone;
            });
            if (!Boolean.TRUE.equals(removed)) throw rejected(DocumentErrorCode.DOCUMENT_NOT_FOUND);
        }
        log.info("Document deleted: documentId={} operation=delete outcome=deleted", documentId);
    }

    @Override
    public DocumentResponse move(Caller caller, UUID documentId, UUID collectionId) {
        if (!caller.admin()) throw new ServiceException(CommonErrorCode.ACCESS_DENIED, "DOCUMENT_MOVE_NOT_ADMIN");
        // The admin's own personal documents and every collection document; another user's personal document does not
        // exist for the admin (ADR-0019).
        DocumentRow current = visible(caller, documentId)
                .orElseThrow(() -> rejected(DocumentErrorCode.DOCUMENT_NOT_FOUND));
        if (Objects.equals(current.collectionId(), collectionId)) return DocumentMapper.toResponse(current);
        DocumentRow moved = transaction.execute(status -> {
            List<UUID> involved = Stream.of(current.collectionId(), collectionId).filter(Objects::nonNull).sorted().toList();
            for (UUID id : involved) {
                if (!collections.lock(id, LockMode.SHARE)) throw rejected(DocumentErrorCode.COLLECTION_NOT_FOUND);
            }
            repository.waitForLocksUpTo(DELETE_LOCK_WAIT);
            if (collectionId == null) {
                repository.lockAccount(current.accountId());
                if (repository.count(current.accountId()) >= properties.maxDocumentsPerAccount()) {
                    throw rejected(DocumentErrorCode.DOCUMENT_LIMIT_REACHED);
                }
            } else {
                repository.lockCollectionQuota(collectionId);
                if (repository.countInCollection(collectionId) >= collectionProperties.maxDocumentsPerCollection()) {
                    throw rejected(DocumentErrorCode.COLLECTION_DOCUMENT_LIMIT_REACHED);
                }
            }
            DocumentRow locked = repository.lockForMove(documentId)
                    .orElseThrow(() -> rejected(DocumentErrorCode.DOCUMENT_NOT_FOUND));
            if (!Objects.equals(locked.collectionId(), current.collectionId())) {
                // Moved by someone else since it was read: the locks taken above are not the right ones.
                throw new ServiceException(CommonErrorCode.REQUEST_CONFLICT, "DOCUMENT_MOVED_CONCURRENTLY");
            }
            repository.move(documentId, collectionId, clock.instant());
            audit.append(AuditEvent.success(AuditAction.DOCUMENT_MOVED, AuditTargetType.DOCUMENT, documentId)
                    .withDetail(collectionId == null ? "TO_PERSONAL" : "TO_COLLECTION"));
            return repository.find(locked.accountId(), documentId).orElseThrow();
        });
        log.info("Document moved: documentId={} to={} operation=move outcome=moved", documentId,
                collectionId == null ? "personal" : "collection");
        return DocumentMapper.toResponse(moved);
    }

    /**
     * A document the caller reads (its principals), or for verso-admin any collection document: management needs the
     * collection's documents, reading through Q&amp;A still needs a grant.
     */
    private Optional<DocumentRow> visible(Caller caller, UUID documentId) {
        Optional<DocumentRow> readable = repository.findReadable(caller.principals(), documentId);
        if (readable.isPresent() || !caller.admin()) return readable;
        return repository.findInAnyCollection(documentId);
    }

    /** A collection the caller reads, or any collection for verso-admin; otherwise it does not exist (IDOR). */
    private CollectionRow visibleCollection(Caller caller, UUID collectionId) {
        return collections.find(caller.principals(), collectionId).filter(c -> c.readable() || caller.admin())
                .orElseThrow(() -> rejected(DocumentErrorCode.COLLECTION_NOT_FOUND));
    }

    private DocumentResponse replayed(DocumentRow row) {
        log.info("Document upload replayed: documentId={} operation=upload outcome=replayed", row.id());
        return DocumentMapper.toResponse(row);
    }

    /** The global handler logs the rejection (code and reason) once; nothing is logged here (reference 7.4). */
    private static DocumentServiceException rejected(DocumentErrorCode code) {
        return new DocumentServiceException(code);
    }
}
