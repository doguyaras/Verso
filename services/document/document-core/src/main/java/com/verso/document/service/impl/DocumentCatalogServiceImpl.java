package com.verso.document.service.impl;

import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.dto.DocumentAccess;
import com.verso.document.api.dto.DocumentInventory;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.DocumentSummary;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.platform.security.authority.VersoRoles;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * {@link DocumentCatalog} (ADR-0023, ADR-0028). Names and ids only, never pages or chunks. The reader's lookup carries
 * the reader's principals in the statement (IDOR, reference 6.5) and returns no name for a document they do not reach;
 * the account-wide lookups are guarded here too, not only by the qa admin
 * endpoint: a caller without {@code verso-admin} is refused whichever module asks (ADR-0019, defence in depth).
 */
@Service
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentCatalogServiceImpl implements DocumentCatalog {

    private final DocumentRepository repository;

    public DocumentCatalogServiceImpl(DocumentRepository repository) {
        this.repository = repository;
    }

    @Override
    public Map<UUID, DocumentAccess> access(Reader reader, Collection<UUID> documentIds) {
        Set<UUID> ids = bounded(documentIds);
        if (ids.isEmpty()) return Map.of();
        Map<UUID, DocumentAccess> access = new LinkedHashMap<>();
        for (DocumentAccess row : repository.access(reader.principals().toArray(String[]::new), ids)) {
            access.put(row.documentId(), row);
        }
        return access;
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public List<DocumentSummary> describeAcrossAccounts(Collection<UUID> documentIds) {
        Set<UUID> ids = bounded(documentIds);
        if (ids.isEmpty()) return List.of();
        return repository.findAllAcrossAccounts(ids).stream().map(DocumentCatalogServiceImpl::summary).toList();
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public DocumentInventory readyDocumentsExcept(Collection<UUID> excluded, int limit) {
        if (limit < 0 || limit > 500) throw new IllegalArgumentException("limit must be 0..500");
        Set<UUID> ids = bounded(excluded);
        List<DocumentSummary> documents = limit == 0 ? List.of()
                : repository.readyExcept(ids, limit).stream().map(DocumentCatalogServiceImpl::summary).toList();
        return new DocumentInventory(repository.countReadyExcept(ids), documents);
    }

    /** Distinct and bounded: a caller cannot turn one lookup into an unbounded statement. */
    private static Set<UUID> bounded(Collection<UUID> ids) {
        Set<UUID> distinct = ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (distinct.size() > MAX_IDS) throw new IllegalArgumentException("at most " + MAX_IDS + " ids");
        return distinct;
    }

    private static DocumentSummary summary(DocumentRow row) {
        return new DocumentSummary(row.id(), row.fileName(), row.format(), row.createdAt());
    }
}
