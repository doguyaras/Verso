package com.verso.document.service.impl;

import com.verso.document.api.DocumentSubjects;
import com.verso.document.api.dto.SubjectDocuments;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.SubjectDocumentRepository;
import com.verso.platform.security.authority.VersoRoles;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DocumentSubjects} (ADR-0034). The personal-document erasure is one short transaction per batch in this
 * module's schema only (operation-consistency 1: local transaction); the caller lists a batch of ids, deletes it, and
 * repeats until the list comes back empty. A document being ingested is deleted all the same: the worker's claim no longer matches and its result is
 * dropped (ADR-0011), exactly as for {@code DELETE /v1/documents/{id}}.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentSubjectsServiceImpl implements DocumentSubjects {

    /**
     * Same bound as the single delete (DocumentServiceImpl): longer than the worker's longest transaction, so a row the
     * worker holds delays the batch; anything longer fails it (503) instead of hanging it.
     */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(15);

    private final SubjectDocumentRepository subjects;
    private final DocumentRepository documents;
    private final TransactionTemplate transaction;

    public DocumentSubjectsServiceImpl(SubjectDocumentRepository subjects, DocumentRepository documents,
                                       TransactionTemplate transaction) {
        this.subjects = subjects;
        this.documents = documents;
        this.transaction = transaction;
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public SubjectDocuments find(String sub) {
        // Read only (rolled back). READ COMMITTED: each statement reads the latest commit, so a document uploaded or
        // deleted meanwhile may show in a count and not in a list; the report is "as of the request", not a snapshot.
        return transaction.execute(status -> {
            status.setRollbackOnly();
            return new SubjectDocuments(
                    subjects.countUploadedBy(sub, true), subjects.uploadedBy(sub, true, MAX_LISTED),
                    subjects.countUploadedBy(sub, false), subjects.uploadedBy(sub, false, MAX_LISTED),
                    subjects.grantedCollections(sub, MAX_LISTED), subjects.createdCollections(sub, MAX_LISTED));
        });
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public List<UUID> personalDocumentIds(String sub, int limit) {
        if (limit < 1 || limit > MAX_DELETE_BATCH) throw new IllegalArgumentException("limit must be 1.." + MAX_DELETE_BATCH);
        return subjects.personalIds(sub, limit);
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public List<UUID> deletePersonalDocuments(String sub, Collection<UUID> documentIds) {
        Set<UUID> ids = documentIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.size() > MAX_DELETE_BATCH) throw new IllegalArgumentException("at most " + MAX_DELETE_BATCH + " ids");
        if (ids.isEmpty()) return List.of();
        return transaction.execute(status -> {
            subjects.lockSubject(sub);
            documents.waitForLocksUpTo(LOCK_WAIT);
            return subjects.deletePersonal(sub, ids);
        });
    }
}
