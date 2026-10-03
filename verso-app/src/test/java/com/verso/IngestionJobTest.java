package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.repository.IngestionRepository.Claim;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionTransactionService;
import com.verso.document.worker.IngestionTransactionService.LostClaimException;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.VersoTestEnvironment;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ingestion job's finer guarantees (phase 4 test review T4-T6, T13-T15): lease renewal per batch, claim-token
 * guards on every write, non-blocking claims, the attempt boundary, the per-poll limit and the schema's own state
 * constraints, all against the real database.
 */
@SpringBootTest
@VersoTestEnvironment
class IngestionJobTest {

    @Autowired
    IngestionWorker worker;

    @Autowired
    IngestionTransactionService tx;

    @Autowired
    DocumentRepository documents;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    private final String account = "acct-job-" + UUID.randomUUID();

    @BeforeEach
    void clean() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        TestEmbeddingModel.INSTANCE.reset();
        while (worker.isPaused()) Thread.sleep(50);
    }

    /** T4: more chunks than one batch; the lease moves forward after each embedding batch. */
    @Test
    void runOnce_whenMoreChunksThanOneBatch_renewsTheLeaseAfterEachBatch() {
        UUID id = upload(TestPdfs.pages(IntStream.range(0, 4000).mapToObj(i -> "word" + i).collect(Collectors.joining(" "))));
        List<Instant> leases = new ArrayList<>();
        TestEmbeddingModel.INSTANCE.onCall(() -> leases.add(jdbc.queryForObject(
                "SELECT locked_until FROM document.document WHERE id = ?", Timestamp.class, id).toInstant()));

        worker.runOnce();

        assertThat(leases).as("one call per batch of 16").hasSizeGreaterThan(2);
        for (int i = 1; i < leases.size(); i++) assertThat(leases.get(i)).isAfter(leases.get(i - 1));
        assertThat(documents.find(account, id).orElseThrow().status().name()).isEqualTo("READY");
    }

    /** T4/T5: a worker whose claim was taken over can neither renew the lease nor store pages. */
    @Test
    void writes_whenTheClaimWasTakenOver_areLostClaimsAndChangeNothing() {
        UUID id = upload(TestPdfs.pages("text"));
        Instant now = Instant.now();
        Claim stale = tx.claimNext(now, now.minusSeconds(1)).orElseThrow();
        Claim fresh = tx.claimNext(now, now.plusSeconds(600)).orElseThrow();
        Timestamp freshLease = lease(id);

        assertThatThrownBy(() -> tx.extendLease(stale, Instant.now(), Instant.now().plusSeconds(3600)))
                .isInstanceOf(LostClaimException.class);
        assertThatThrownBy(() -> tx.storePages(stale, List.of("forged"), Instant.now())).isInstanceOf(LostClaimException.class);
        assertThatThrownBy(() -> tx.release(stale, Instant.now())).isInstanceOf(LostClaimException.class);

        assertThat(lease(id)).isEqualTo(freshLease);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document_file WHERE document_id = ?", Integer.class, id))
                .as("the PDF is still there for the real owner").isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document_page WHERE document_id = ?", Integer.class, id))
                .isZero();
        assertThat(fresh.documentId()).isEqualTo(id);
    }

    /** T14: SKIP LOCKED: a row locked by another transaction is skipped at once, not waited for. */
    @Test
    void claimNext_whenARowIsLockedElsewhere_takesTheNextOneWithoutWaiting() throws Exception {
        UUID locked = upload(TestPdfs.pages("locked"));
        UUID free = upload(TestPdfs.pages("free"));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transaction.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM document.document WHERE id = ? FOR UPDATE", locked);
            held.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        held.await();
        long started = System.nanoTime();
        Claim claim = tx.claimNext(Instant.now(), Instant.now().plusSeconds(600)).orElseThrow();
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(claim.documentId()).isEqualTo(free);
        assertThat(took).isLessThan(Duration.ofSeconds(2));
    }

    /** T13: the fifth attempt is still a real attempt; only the sixth claim is the poison limit. */
    @Test
    void runOnce_whenTheFifthAttemptComes_stillProcessesTheDocument() {
        UUID id = upload(TestPdfs.pages("text"));
        jdbc.update("UPDATE document.document SET attempts = 4 WHERE id = ?", id);
        worker.runOnce();
        assertThat(documents.find(account, id).orElseThrow().status().name()).isEqualTo("READY");
        assertThat(TestEmbeddingModel.INSTANCE.calls()).isOne();
    }

    /** T15: one poll processes at most verso.document.ingestion.max-documents-per-poll documents (10). */
    @Test
    void runOnce_whenMoreAreDueThanThePerPollLimit_claimsTen() {
        for (int i = 0; i < 12; i++) upload(TestPdfs.pages("text " + i));
        assertThat(worker.runOnce()).isEqualTo(10);
        assertThat(worker.runOnce()).isEqualTo(2);
    }

    /** T15: with the scheduled poll switched off (tests, operators) nothing is claimed. */
    @Test
    void poll_whenDisabled_claimsNothing() {
        UUID id = upload(TestPdfs.pages("text"));
        worker.poll();
        assertThat(documents.find(account, id).orElseThrow().status().name()).isEqualTo("PENDING");
    }

    /** T6: the schema refuses inconsistent job states on its own (V1 CHECK constraints). */
    @Test
    void schema_whenAJobStateIsInconsistent_rejectsIt() {
        UUID id = upload(TestPdfs.pages("text"));
        for (String update : List.of(
                "UPDATE document.document SET status = 'FAILED' WHERE id = ?",
                "UPDATE document.document SET status = 'READY', failure_reason = 'NO_TEXT' WHERE id = ?",
                "UPDATE document.document SET status = 'PROCESSING' WHERE id = ?",
                "UPDATE document.document SET claim_token = gen_random_uuid(), locked_until = now() WHERE id = ?",
                "UPDATE document.document SET status = 'UNKNOWN' WHERE id = ?",
                "UPDATE document.document SET file_name = '' WHERE id = ?")) {
            assertThatThrownBy(() -> jdbc.update(update, id)).as(update).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    /** T6: the claim and vector indexes exist with the operator class retrieval relies on. */
    @Test
    void schema_whenMigrated_hasTheClaimAndCosineIndexes() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'document'", String.class);
        assertThat(indexes).anySatisfy(i -> assertThat(i).contains("idx_document_claim").contains("WHERE"));
        assertThat(indexes).anySatisfy(i -> assertThat(i).contains("hnsw").contains("vector_cosine_ops"));
        assertThat(indexes).anySatisfy(i -> assertThat(i).contains("uq_document_account_idempotency_key"));
    }

    private UUID upload(byte[] pdf) {
        DocumentRow row = documents.insert(account, "job.pdf", DocumentFormat.PDF, pdf.length, null, Instant.now());
        documents.insertFile(row.id(), pdf);
        return row.id();
    }

    private Timestamp lease(UUID id) {
        return jdbc.queryForObject("SELECT locked_until FROM document.document WHERE id = ?", Timestamp.class, id);
    }
}
