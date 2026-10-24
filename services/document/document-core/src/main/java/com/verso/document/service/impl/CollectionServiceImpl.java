package com.verso.document.service.impl;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.document.api.dto.CollectionGrantRequest;
import com.verso.document.api.dto.CollectionGrantResponse;
import com.verso.document.api.dto.CollectionRequest;
import com.verso.document.api.dto.CollectionResponse;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.enums.PrincipalType;
import com.verso.document.config.CollectionProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.CollectionRepository;
import com.verso.document.repository.CollectionRepository.CollectionRow;
import com.verso.document.repository.CollectionRepository.GrantRow;
import com.verso.document.repository.CollectionRepository.LockMode;
import com.verso.document.service.Caller;
import com.verso.document.service.CollectionService;
import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.outbox.DocumentAuditOutbox;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link CollectionService} (ADR-0028). Every grant change runs in one transaction with the collection row locked FOR
 * UPDATE and rewrites the principal of each of the collection's documents (document_principal) in the same
 * transaction: the next retrieval after the commit already uses it. Logs carry ids and fixed words only: never a
 * collection name, a group or a sub (security-rules 4, llm-rules 2.1). Audit records in the same transaction, through the module outbox (ADR-0039).
 */
@Service
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CollectionServiceImpl implements CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionServiceImpl.class);

    private final CollectionRepository repository;
    private final CollectionProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final DocumentAuditOutbox audit;

    public CollectionServiceImpl(CollectionRepository repository, CollectionProperties properties,
                                 TransactionTemplate transaction, Clock clock, DocumentAuditOutbox audit) {
        this.repository = repository;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
        this.audit = audit;
    }

    @Override
    public PageResponse<CollectionResponse> list(Caller caller, int page, int size) {
        String[] principals = caller.principals();
        if (caller.admin()) {
            return PageResponse.of(repository.listAll(principals, page, size).stream().map(c -> response(c, caller)).toList(),
                    page, size, repository.countAll());
        }
        return PageResponse.of(repository.listReadable(principals, page, size).stream().map(c -> response(c, caller)).toList(),
                page, size, repository.countReadable(principals));
    }

    @Override
    public CollectionResponse get(Caller caller, UUID collectionId) {
        return response(visible(caller, collectionId), caller);
    }

    @Override
    public CollectionResponse create(Caller caller, CollectionRequest request) {
        requireAdmin(caller);
        CollectionRow created;
        try {
            created = transaction.execute(status -> {
                // One installation-wide lock: the count check and the insert cannot interleave (check-then-act).
                repository.lockInstallation();
                if (repository.countAll() >= properties.maxCollections()) {
                    throw rejected(DocumentErrorCode.COLLECTION_LIMIT_REACHED);
                }
                CollectionRow row = repository.insert(request.name().strip(), description(request),
                        caller.account().value(), clock.instant());
                audit.append(AuditEvent.success(AuditAction.COLLECTION_CREATED, AuditTargetType.COLLECTION, row.id()));
                return row;
            });
        } catch (DuplicateKeyException e) {
            throw rejected(DocumentErrorCode.COLLECTION_NAME_TAKEN);
        }
        log.info("Collection created: collectionId={} operation=create outcome=created", created.id());
        return response(created, caller);
    }

    @Override
    public CollectionResponse update(Caller caller, UUID collectionId, CollectionRequest request) {
        requireAdmin(caller);
        boolean updated;
        try {
            updated = Boolean.TRUE.equals(transaction.execute(status -> {
                boolean changed = repository.update(collectionId, request.name().strip(), description(request),
                        clock.instant());
                if (changed) {
                    audit.append(AuditEvent.success(AuditAction.COLLECTION_UPDATED, AuditTargetType.COLLECTION,
                            collectionId));
                }
                return changed;
            }));
        } catch (DuplicateKeyException e) {
            throw rejected(DocumentErrorCode.COLLECTION_NAME_TAKEN);
        }
        if (!updated) throw rejected(DocumentErrorCode.COLLECTION_NOT_FOUND);
        log.info("Collection updated: collectionId={} operation=update outcome=updated", collectionId);
        return get(caller, collectionId);
    }

    @Override
    public void delete(Caller caller, UUID collectionId) {
        requireAdmin(caller);
        transaction.executeWithoutResult(status -> {
            // FOR UPDATE: an upload or move into the collection holds it shared, so the emptiness check is exact.
            if (!repository.lock(collectionId, LockMode.UPDATE)) throw rejected(DocumentErrorCode.COLLECTION_NOT_FOUND);
            if (!repository.deleteIfEmpty(collectionId)) throw rejected(DocumentErrorCode.COLLECTION_NOT_EMPTY);
            audit.append(AuditEvent.success(AuditAction.COLLECTION_DELETED, AuditTargetType.COLLECTION, collectionId));
        });
        log.info("Collection deleted: collectionId={} operation=delete outcome=deleted", collectionId);
    }

    @Override
    public List<CollectionGrantResponse> grants(Caller caller, UUID collectionId) {
        requireAdmin(caller);
        visible(caller, collectionId);
        return repository.grants(collectionId).stream().map(CollectionServiceImpl::grantResponse).toList();
    }

    @Override
    public GrantResult grant(Caller caller, UUID collectionId, CollectionGrantRequest request) {
        requireAdmin(caller);
        String principal = principal(request);
        GrantResult result = transaction.execute(status -> {
            if (!repository.lock(collectionId, LockMode.UPDATE)) throw rejected(DocumentErrorCode.COLLECTION_NOT_FOUND);
            Optional<GrantRow> existing = repository.findGrant(collectionId, principal);
            if (existing.isPresent()) return new GrantResult(grantResponse(existing.get()), false);
            if (repository.countGrants(collectionId) >= properties.maxGrantsPerCollection()) {
                throw rejected(DocumentErrorCode.COLLECTION_GRANT_LIMIT_REACHED);
            }
            GrantResult added = new GrantResult(grantResponse(repository.grant(collectionId, principal,
                    caller.account().value(), clock.instant())), true);
            audit.append(AuditEvent.success(AuditAction.COLLECTION_GRANT_ADDED, AuditTargetType.COLLECTION, collectionId)
                    .withDetail(request.principalType().name()));
            return added;
        });
        if (result.created()) {
            log.info("Collection granted: collectionId={} grantId={} principalType={} operation=grant outcome=granted",
                    collectionId, result.grant().id(), request.principalType());
        }
        return result;
    }

    @Override
    public void revoke(Caller caller, UUID collectionId, UUID grantId) {
        requireAdmin(caller);
        String principal = transaction.execute(status -> {
            if (!repository.lock(collectionId, LockMode.UPDATE)) throw rejected(DocumentErrorCode.COLLECTION_NOT_FOUND);
            String revoked = repository.revoke(collectionId, grantId)
                    .orElseThrow(() -> rejected(DocumentErrorCode.COLLECTION_GRANT_NOT_FOUND));
            audit.append(AuditEvent.success(AuditAction.COLLECTION_GRANT_REMOVED, AuditTargetType.COLLECTION, collectionId)
                    .withDetail(typeOf(revoked).name()));
            return revoked;
        });
        PrincipalType type = typeOf(principal);
        log.info("Collection grant revoked: collectionId={} grantId={} principalType={} operation=revoke outcome=revoked",
                collectionId, grantId, type);
    }

    /** A collection the caller reads, or any collection for verso-admin; otherwise it does not exist (IDOR). */
    private CollectionRow visible(Caller caller, UUID collectionId) {
        return repository.find(caller.principals(), collectionId).filter(c -> c.readable() || caller.admin())
                .orElseThrow(() -> rejected(DocumentErrorCode.COLLECTION_NOT_FOUND));
    }

    /** The controller declares verso-admin; the use case keeps the rule too (defence in depth, ADR-0019). */
    private static void requireAdmin(Caller caller) {
        if (!caller.admin()) throw new ServiceException(CommonErrorCode.ACCESS_DENIED, "COLLECTION_NOT_ADMIN");
    }

    /** The stored principal: exactly what the token will carry, so no trimming or case folding. */
    private static String principal(CollectionGrantRequest request) {
        String prefix = request.principalType() == PrincipalType.GROUP ? Reader.GROUP_PREFIX : Reader.USER_PREFIX;
        return prefix + request.principal();
    }

    private static PrincipalType typeOf(String principal) {
        return principal.startsWith(Reader.GROUP_PREFIX) ? PrincipalType.GROUP : PrincipalType.USER;
    }

    private static String description(CollectionRequest request) {
        if (request.description() == null) return null;
        String stripped = request.description().strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static CollectionResponse response(CollectionRow row, Caller caller) {
        return new CollectionResponse(row.id(), row.name(), row.description(), row.documentCount(), row.readable(),
                caller.admin(), row.createdAt(), row.updatedAt());
    }

    private static CollectionGrantResponse grantResponse(GrantRow row) {
        PrincipalType type = typeOf(row.principal());
        String value = row.principal().substring(type == PrincipalType.GROUP
                ? Reader.GROUP_PREFIX.length() : Reader.USER_PREFIX.length());
        return new CollectionGrantResponse(row.id(), type, value, row.createdAt());
    }

    private static DocumentServiceException rejected(DocumentErrorCode code) {
        return new DocumentServiceException(code);
    }
}
