package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.repository.IngestionRepository.Claim;
import com.verso.document.repository.IngestionRepository.StoredChunk;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionTransactionService;
import com.verso.document.worker.IngestionTransactionService.LostClaimException;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.VersoTestEnvironment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The ingestion worker against the real database (ADR-0003, ADR-0011, reference 11.1): READY with pages, chunks and
 * vectors and without the PDF; permanent failures without retry and without leftovers; temporary failures with
 * backoff; leases that expire and claims that are lost; parallel claims; and logs without file names or text.
 */
@SpringBootTest
@VersoTestEnvironment
class IngestionWorkerTest {

    private static final String MARKER = "MarkerQuarterlySalaryOfJaneDoe";

    @Autowired
    IngestionWorker worker;

    @Autowired
    IngestionTransactionService tx;

    @Autowired
    DocumentRepository documents;

    @Autowired
    JdbcTemplate jdbc;

    private final String account = "acct-ingest-" + UUID.randomUUID();
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document.document");
        TestEmbeddingModel.INSTANCE.reset();
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        appender.stop();
        TestEmbeddingModel.INSTANCE.reset();
    }

    @Test
    void runOnce_whenPdfHasText_storesPagesChunksAndVectorsAndDropsThePdf() {
        UUID id = upload(TestPdfs.pages("Alpha page one text.", "", "Gamma page three text."));

        assertThat(worker.runOnce()).isOne();

        DocumentRow row = documents.find(account, id).orElseThrow();
        assertThat(row.status().name()).isEqualTo("READY");
        assertThat(row.pageCount()).isEqualTo(3);
        assertThat(row.chunkCount()).isEqualTo(2);
        assertThat(count("document_file", id)).as("the PDF is gone (ADR-0011)").isZero();
        assertThat(count("document_page", id)).isEqualTo(3);
        List<Map<String, Object>> chunks = jdbc.queryForList("""
                SELECT account_id, page_number, chunk_index, embedding_model, extensions.vector_dims(embedding) AS dims
                FROM document.document_chunk WHERE document_id = ? ORDER BY chunk_index""", id);
        assertThat(chunks).extracting(c -> c.get("page_number")).containsExactly(1, 3);
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.get("account_id")).as("ownership copied for retrieval (llm-rules 4.1)").isEqualTo(account);
            assertThat(c.get("embedding_model")).as("llm-rules 6.1").isEqualTo("test-embedding");
            assertThat(c.get("dims")).isEqualTo(1024);
        });
        assertThat(jdbc.queryForObject("SELECT claim_token IS NULL AND locked_until IS NULL FROM document.document WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(worker.runOnce()).as("nothing left").isZero();
    }

    /** Permanent reasons are not retried, the model is never called, and no content stays behind. */
    @Test
    void runOnce_whenPdfIsUnusable_failsAtOnceWithItsReasonAndKeepsNoContent() {
        UUID encrypted = upload(TestPdfs.encrypted("hidden"));
        UUID blank = upload(TestPdfs.blank(2));
        UUID fake = upload(TestPdfs.fakePdf());

        assertThat(worker.runOnce()).isEqualTo(3);

        assertFailed(encrypted, "ENCRYPTED");
        assertFailed(blank, "NO_TEXT");
        assertFailed(fake, "NOT_A_PDF");
        assertThat(TestEmbeddingModel.INSTANCE.calls()).isZero();
        for (UUID id : List.of(encrypted, blank, fake)) {
            assertThat(count("document_file", id) + count("document_page", id) + count("document_chunk", id)).isZero();
        }
    }

    /** A model outage is temporary: back to PENDING with a later retry time; the pages stay, the PDF is gone. */
    @Test
    void runOnce_whenTheModelFails_retriesLaterWithBackoff() {
        UUID id = upload(TestPdfs.pages("Some text to embed."));
        TestEmbeddingModel.INSTANCE.failWith(new IllegalStateException("model down"));
        Instant before = Instant.now();

        assertThat(worker.runOnce()).isOne();

        Map<String, Object> job = job(id);
        assertThat(job.get("status")).isEqualTo("PENDING");
        assertThat(job.get("attempts")).isEqualTo(1);
        assertThat(((java.sql.Timestamp) job.get("next_attempt_at")).toInstant()).isAfter(before.plusSeconds(25));
        assertThat(count("document_page", id)).as("parsed once, kept for the retry").isOne();
        assertThat(count("document_file", id)).isZero();
        assertThat(worker.runOnce()).as("not due yet").isZero();
    }

    @Test
    void runOnce_whenTheModelKeepsFailing_givesUpAfterMaxAttempts() {
        UUID id = upload(TestPdfs.pages("Some text."));
        TestEmbeddingModel.INSTANCE.failWith(new IllegalStateException("model down"));
        for (int attempt = 1; attempt <= 5; attempt++) {
            // Due by any clock: the database container's clock may run slightly ahead of the JVM's.
            jdbc.update("UPDATE document.document SET next_attempt_at = now() - interval '1 minute' WHERE id = ?", id);
            assertThat(worker.runOnce()).isOne();
        }
        assertFailed(id, "PROCESSING_FAILED");
        assertThat(count("document_page", id)).isZero();
    }

    /** A model that answers with another vector length is a fault, never stored (column vector(1024)). */
    @Test
    void runOnce_whenTheModelAnswersWithOtherDimensions_storesNothing() {
        UUID id = upload(TestPdfs.pages("Some text."));
        TestEmbeddingModel.INSTANCE.answerWithDimensions(768);
        worker.runOnce();
        assertThat(job(id).get("status")).isEqualTo("PENDING");
        assertThat(count("document_chunk", id)).isZero();
    }

    /** Poison file: a document claimed more often than allowed (the worker died each time) ends FAILED. */
    @Test
    void runOnce_whenClaimedMoreOftenThanAllowed_failsWithoutProcessing() {
        UUID id = upload(TestPdfs.pages("text"));
        jdbc.update("UPDATE document.document SET attempts = 5 WHERE id = ?", id);
        worker.runOnce();
        assertFailed(id, "PROCESSING_FAILED");
        assertThat(TestEmbeddingModel.INSTANCE.calls()).isZero();
    }

    /** Reference 11.1: after the lease expired another worker owns the row; the old one can change nothing. */
    @Test
    void claim_whenLeaseExpired_isTakenOverAndTheOldWorkerCannotComplete() {
        UUID id = upload(TestPdfs.pages("text"));
        Instant now = Instant.now();
        Claim stale = tx.claimNext(now, now.minusSeconds(1)).orElseThrow();
        Claim fresh = tx.claimNext(now, now.plusSeconds(600)).orElseThrow();
        assertThat(fresh.documentId()).isEqualTo(id);
        assertThat(fresh.token()).isNotEqualTo(stale.token());
        assertThat(fresh.attempts()).isEqualTo(2);

        assertThatThrownBy(() -> tx.complete(stale, 1, List.of(chunk()), "test-embedding", Instant.now()))
                .isInstanceOf(LostClaimException.class);
        assertThatThrownBy(() -> tx.fail(stale, com.verso.document.api.enums.DocumentFailureReason.NO_TEXT, Instant.now()))
                .isInstanceOf(LostClaimException.class);
        assertThat(job(id).get("status")).isEqualTo("PROCESSING");
        assertThat(count("document_chunk", id)).isZero();

        tx.complete(fresh, 1, List.of(chunk()), "test-embedding", Instant.now());
        assertThat(job(id).get("status")).isEqualTo("READY");
    }

    /** A document deleted while it is processed: the worker's result is dropped, nothing is re-created. */
    @Test
    void complete_whenTheDocumentWasDeletedMeanwhile_isALostClaim() {
        UUID id = upload(TestPdfs.pages("text"));
        Claim claim = tx.claimNext(Instant.now(), Instant.now().plusSeconds(600)).orElseThrow();
        documents.delete(account, id);
        assertThatThrownBy(() -> tx.complete(claim, 1, List.of(chunk()), "test-embedding", Instant.now()))
                .isInstanceOf(LostClaimException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document_chunk", Integer.class)).isZero();
    }

    /** Parallel claims take different rows and never wait for each other (SKIP LOCKED, reference 11.1). */
    @Test
    void claimNext_whenCalledInParallel_handsOutEachDocumentOnce() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) ids.add(upload(TestPdfs.pages("text " + i)));
        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Optional<Claim>>> tasks = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            tasks.add(() -> {
                start.await();
                Instant now = Instant.now();
                return tx.claimNext(now, now.plusSeconds(600));
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Future<Optional<Claim>>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            List<UUID> claimed = new ArrayList<>();
            for (Future<Optional<Claim>> future : futures) future.get().ifPresent(c -> claimed.add(c.documentId()));
            assertThat(claimed).hasSize(6).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
        }
    }

    /** llm-rules 2.1: neither the file name nor any page text reaches a log line, an argument, the MDC or a cause. */
    @Test
    void ingestion_whenSuccessfulOrFailing_logsNoFileNameOrText() {
        upload(TestPdfs.pages(MARKER + " in the text."), MARKER + ".pdf");
        upload(TestPdfs.encrypted(MARKER), MARKER + "-locked.pdf");
        worker.runOnce();
        TestEmbeddingModel.INSTANCE.failWith(new IllegalStateException(MARKER + " in a model error"));
        upload(TestPdfs.pages(MARKER + " again."), MARKER + "-2.pdf");
        worker.runOnce();

        assertThat(appender.list).as("outcomes are logged").anyMatch(e -> e.getFormattedMessage().contains("outcome=READY"))
                .anyMatch(e -> e.getFormattedMessage().contains("outcome=FAILED"))
                .anyMatch(e -> e.getFormattedMessage().contains("outcome=retry"));
        for (ILoggingEvent event : appender.list) {
            StringBuilder text = new StringBuilder(event.getFormattedMessage()).append(event.getMDCPropertyMap());
            if (event.getArgumentArray() != null) Stream.of(event.getArgumentArray()).forEach(a -> text.append(' ').append(a));
            for (var p = event.getThrowableProxy(); p != null; p = p.getCause()) text.append(' ').append(p.getMessage());
            assertThat(text.toString()).as(event.getLoggerName()).doesNotContain(MARKER).doesNotContain(account);
        }
    }

    private UUID upload(byte[] pdf) {
        return upload(pdf, "test.pdf");
    }

    private UUID upload(byte[] pdf, String fileName) {
        DocumentRow row = documents.insert(account, fileName, pdf.length, null, Instant.now());
        documents.insertFile(row.id(), pdf);
        return row.id();
    }

    private int count(String table, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM document." + table + " WHERE document_id = ?", Integer.class, id);
    }

    private Map<String, Object> job(UUID id) {
        return jdbc.queryForMap("SELECT status, attempts, next_attempt_at FROM document.document WHERE id = ?", id);
    }

    private void assertFailed(UUID id, String reason) {
        DocumentRow row = documents.find(account, id).orElseThrow();
        assertThat(row.status().name()).isEqualTo("FAILED");
        assertThat(row.failureReason().name()).isEqualTo(reason);
    }

    private static StoredChunk chunk() {
        float[] vector = new float[1024];
        vector[0] = 1f;
        return new StoredChunk(1, 0, "text", vector);
    }
}
