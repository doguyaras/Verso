package com.verso.document.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.repository.IngestionRepository;
import com.verso.document.repository.IngestionRepository.Claim;
import com.verso.document.repository.IngestionRepository.StoredChunk;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions of the ingestion worker (reference 4.3: the remote embedding call is never made inside one,
 * TransactionBoundaryRulesTest). Each write first re-locks the claimed row by its claim token; a lost claim changes
 * nothing and is reported as {@link LostClaimException}.
 */
@Service
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IngestionTransactionService {

    /** The lease expired and another worker took the document, or the owner deleted it meanwhile. */
    public static class LostClaimException extends RuntimeException {
        public LostClaimException() {
            super("claim lost", null, false, false);
        }
    }

    private final IngestionRepository repository;

    public IngestionTransactionService(IngestionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Optional<Claim> claimNext(Instant now, Instant lockedUntil) {
        return repository.claimNext(now, lockedUntil);
    }

    @Transactional(readOnly = true)
    public List<String> loadPages(Claim claim) {
        return repository.loadPages(claim.documentId());
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> loadFile(Claim claim) {
        return repository.loadFile(claim.documentId());
    }

    @Transactional
    public void storePages(Claim claim, List<String> pages, Instant now) {
        hold(claim, now);
        repository.replacePagesAndDropFile(claim.documentId(), pages);
    }

    @Transactional
    public void extendLease(Claim claim, Instant now, Instant lockedUntil) {
        if (!repository.extendLease(claim, now, lockedUntil)) throw new LostClaimException();
    }

    @Transactional
    public void complete(Claim claim, int pageCount, List<StoredChunk> chunks, String model, Instant now) {
        hold(claim, now);
        repository.complete(claim, pageCount, chunks, model, now);
    }

    @Transactional
    public void fail(Claim claim, DocumentFailureReason reason, Instant now) {
        hold(claim, now);
        repository.fail(claim, reason, now);
    }

    @Transactional
    public void release(Claim claim, Instant now) {
        hold(claim, now);
        repository.release(claim, now);
    }

    @Transactional
    public void retryLater(Claim claim, Instant nextAttemptAt, Instant now) {
        hold(claim, now);
        repository.retryLater(claim, nextAttemptAt, now);
    }

    private void hold(Claim claim, Instant now) {
        if (!repository.holdClaim(claim, now)) throw new LostClaimException();
    }
}
