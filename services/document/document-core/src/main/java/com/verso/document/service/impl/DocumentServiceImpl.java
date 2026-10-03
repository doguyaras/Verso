package com.verso.document.service.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.api.dto.DocumentResponse;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.config.DocumentProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.service.DocumentMapper;
import com.verso.document.service.DocumentService;
import com.verso.document.service.FileNames;
import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.security.web.AccountId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * /v1/documents use cases (ADR-0011). Upload is a database write only: no parsing and no model call on the request
 * path (repo-context section 3: zero remote calls, 2 s budget); the worker does the rest. Logs: ids, sizes and fixed
 * outcomes, never the file name (llm-rules 2.1).
 */
@Service
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentServiceImpl implements DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);
    /** ISO 32000: the header is "%PDF-"; readers accept it within the first 1024 bytes. */
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int MAGIC_WINDOW = 1024;
    /** Longer than the worker's longest transaction (storing the chunks of a document at the text limit, ~6 s). */
    private static final Duration DELETE_LOCK_WAIT = Duration.ofSeconds(15);

    private final DocumentRepository repository;
    private final DocumentProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public DocumentServiceImpl(DocumentRepository repository, DocumentProperties properties,
                               TransactionTemplate transaction, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public DocumentResponse upload(AccountId account, String fileName, byte[] content, UUID idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<DocumentRow> earlier = repository.findByIdempotencyKey(account.value(), idempotencyKey);
            if (earlier.isPresent()) return replayed(earlier.get());
        }
        if (content.length == 0) throw rejected(DocumentErrorCode.DOCUMENT_EMPTY);
        if (content.length > properties.maxFileSize().toBytes()) {
            // The multipart limit (same value) normally answers first; one code for one rule (review P2).
            throw new ServiceException(CommonErrorCode.PAYLOAD_TOO_LARGE, "DOCUMENT_TOO_LARGE");
        }
        if (!startsLikePdf(content)) throw rejected(DocumentErrorCode.DOCUMENT_NOT_PDF);
        String name = FileNames.sanitize(fileName);
        DocumentRow created;
        try {
            created = transaction.execute(status -> {
                // Count and insert under a per-account lock: parallel uploads cannot overrun the quota (review C6).
                repository.lockAccount(account.value());
                if (repository.count(account.value()) >= properties.maxDocumentsPerAccount()) {
                    throw rejected(DocumentErrorCode.DOCUMENT_LIMIT_REACHED);
                }
                if (repository.countQueued(account.value()) >= properties.maxQueuedPerAccount()) {
                    throw rejected(DocumentErrorCode.DOCUMENT_QUEUE_FULL);
                }
                DocumentRow row = repository.insert(account.value(), name, content.length, idempotencyKey, clock.instant());
                repository.insertFile(row.id(), content);
                return row;
            });
        } catch (DuplicateKeyException e) {
            // Two uploads with the same key at once: the first one won (reference 6.4).
            return replayed(repository.findByIdempotencyKey(account.value(), idempotencyKey).orElseThrow(() -> e));
        }
        log.info("Document upload accepted: documentId={} sizeBytes={} operation=upload outcome=accepted",
                created.id(), created.sizeBytes());
        return DocumentMapper.toResponse(created);
    }

    @Override
    public PageResponse<DocumentResponse> list(AccountId account, int page, int size) {
        List<DocumentResponse> rows = repository.list(account.value(), page, size).stream()
                .map(DocumentMapper::toResponse).toList();
        return PageResponse.of(rows, page, size, repository.count(account.value()));
    }

    @Override
    public DocumentResponse get(AccountId account, UUID documentId) {
        return repository.find(account.value(), documentId).map(DocumentMapper::toResponse)
                .orElseThrow(() -> rejected(DocumentErrorCode.DOCUMENT_NOT_FOUND));
    }

    @Override
    public void delete(AccountId account, UUID documentId) {
        Boolean deleted = transaction.execute(status -> {
            repository.waitForLocksUpTo(DELETE_LOCK_WAIT);
            return repository.delete(account.value(), documentId);
        });
        if (!Boolean.TRUE.equals(deleted)) throw rejected(DocumentErrorCode.DOCUMENT_NOT_FOUND);
        log.info("Document deleted: documentId={} operation=delete outcome=deleted", documentId);
    }

    private DocumentResponse replayed(DocumentRow row) {
        log.info("Document upload replayed: documentId={} operation=upload outcome=replayed", row.id());
        return DocumentMapper.toResponse(row);
    }

    private static boolean startsLikePdf(byte[] content) {
        int window = Math.min(content.length, MAGIC_WINDOW) - PDF_MAGIC.length;
        for (int offset = 0; offset <= window; offset++) {
            boolean match = true;
            for (int i = 0; i < PDF_MAGIC.length && match; i++) match = content[offset + i] == PDF_MAGIC[i];
            if (match) return true;
        }
        return false;
    }

    /** The global handler logs the rejection (code and reason) once; nothing is logged here (reference 7.4). */
    private static DocumentServiceException rejected(DocumentErrorCode code) {
        return new DocumentServiceException(code);
    }
}
