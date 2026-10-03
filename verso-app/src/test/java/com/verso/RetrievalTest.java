package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.VersoPostgres;
import com.verso.support.VersoTestEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Retrieval against the real database with pgvector (llm-rules 4.1, 4.2, 6.1; ADR-0011): ownership is a condition of the
 * vector query, only READY documents count, a deleted document is gone, and chunks of another embedding model fail
 * closed instead of being compared. The test embedding model maps "topic-x" markers to one direction per topic.
 */
@SpringBootTest
@VersoTestEnvironment
class RetrievalTest {

    @Autowired
    DocumentRetrieval retrieval;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    private final String alice = "acct-alice-" + UUID.randomUUID();
    private final String bob = "acct-bob-" + UUID.randomUUID();

    @BeforeEach
    void clean() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        TestEmbeddingModel.INSTANCE.reset();
        while (worker.isPaused()) Thread.sleep(50);
    }

    /** llm-rules 4.1: Bob's passage on the very same topic never reaches Alice, not even to fill top-k. */
    @Test
    void search_whenAnotherAccountHasTheSameTopic_returnsOnlyTheCallersPassages() {
        UUID mine = ready(alice, "Alice topic-leave policy: twenty days of annual leave.");
        for (int i = 0; i < 6; i++) ready(bob, "Bob topic-leave policy number " + i + ".");

        List<RetrievedPassage> found = retrieval.search(alice, "topic-leave how many days?", 5);

        assertThat(found).isNotEmpty().allSatisfy(p -> assertThat(p.documentId()).isEqualTo(mine));
        assertThat(found.getFirst().similarity()).isGreaterThan(0.9);
        assertThat(found.getFirst().pageNumber()).isEqualTo(1);
        assertThat(found.getFirst().fileName()).isEqualTo("test.pdf");
        assertThat(retrieval.search(bob, "topic-leave", 10)).hasSize(6)
                .noneSatisfy(p -> assertThat(p.documentId()).isEqualTo(mine));
    }

    @Test
    void search_whenPassagesAreRanked_returnsTheBestFirstAndRespectsTopK() {
        ready(alice, "topic-leave annual leave rules.");
        ready(alice, "topic-salary payroll rules.");
        ready(alice, "topic-remote working from home rules.");

        List<RetrievedPassage> found = retrieval.search(alice, "topic-salary when is payday?", 2);

        assertThat(found).hasSize(2);
        assertThat(found.getFirst().content()).contains("payroll");
        assertThat(found.get(0).similarity()).isGreaterThanOrEqualTo(found.get(1).similarity());
    }

    /** Only READY documents are searched: a document still waiting or FAILED has no chunks to offer. */
    @Test
    void search_whenADocumentIsNotReady_ignoresIt() {
        UUID id = ready(alice, "topic-leave being processed again");
        assertThat(retrieval.search(alice, "topic-leave", 5)).hasSize(1);
        // The same chunks, but the document is back in the queue: it does not count until READY again.
        jdbc.update("UPDATE document.document SET status = 'PENDING', next_attempt_at = now() + interval '1 hour' "
                + "WHERE id = ?", id);
        assertThat(retrieval.search(alice, "topic-leave", 5)).isEmpty();
    }

    /** llm-rules 4.2: after deletion the document is gone from retrieval. */
    @Test
    void search_whenTheDocumentWasDeleted_findsNothing() {
        UUID id = ready(alice, "topic-leave to be deleted");
        assertThat(retrieval.search(alice, "topic-leave", 5)).isNotEmpty();
        documents.delete(alice, id);
        assertThat(retrieval.search(alice, "topic-leave", 5)).isEmpty();
    }

    /** llm-rules 6.1: vectors of another model are not compared; the search fails closed with a code. */
    @Test
    void search_whenChunksCameFromAnotherModel_failsClosed() throws Exception {
        UUID id = ready(alice, "topic-leave old index");
        // The application role cannot update chunks (V1); an index of an older model is set up as the superuser.
        try (java.sql.Connection admin = java.sql.DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(),
                VersoPostgres.POSTGRES.getUsername(), VersoPostgres.POSTGRES.getPassword());
             java.sql.PreparedStatement update = admin.prepareStatement(
                     "UPDATE document.document_chunk SET embedding_model = 'old-model' WHERE document_id = ?")) {
            update.setObject(1, id);
            update.executeUpdate();
        }

        assertThatThrownBy(() -> retrieval.search(alice, "topic-leave", 5))
                .isInstanceOfSatisfying(DocumentServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DocumentErrorCode.DOCUMENT_REINDEX_REQUIRED));
        assertThat(retrieval.search(bob, "topic-leave", 5)).as("other accounts are unaffected").isEmpty();
    }

    @Test
    void search_whenTheEmbeddingModelIsDown_answersModelUnavailable() {
        ready(alice, "topic-leave");
        TestEmbeddingModel.INSTANCE.failWith(new IllegalStateException("down"));
        assertThatThrownBy(() -> retrieval.search(alice, "topic-leave", 5))
                .isInstanceOfSatisfying(DocumentServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE));
    }

    /** ADR-0008, phase 5 review R3: a hanging embedding model answers 503 after the timeout, not after 90 s. */
    @Test
    void search_whenTheEmbeddingModelHangs_givesUpAfterTheTimeout() throws Exception {
        ready(alice, "topic-leave");
        CountDownLatch release = new CountDownLatch(1);
        TestEmbeddingModel.INSTANCE.onCall(() -> await(release));
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> retrieval.search(alice, "topic-leave", 5))
                    .isInstanceOfSatisfying(DocumentServiceException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isBetween(Duration.ofMillis(1900), Duration.ofSeconds(8));
        } finally {
            release.countDown();
        }
    }

    /** Phase 5 review L3/S1: at most 4 question embeddings in flight; a hung call keeps its slot until it really ends. */
    @Test
    void search_whenAllEmbeddingSlotsAreTaken_failsFastWithoutCallingTheModel() throws Exception {
        ready(alice, "topic-leave");
        CountDownLatch release = new CountDownLatch(1);
        // A model call that ignores the interrupt: it really hangs until released.
        TestEmbeddingModel.INSTANCE.onCall(() -> awaitIgnoringInterrupts(release));
        int before = TestEmbeddingModel.INSTANCE.calls();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 4; i++) pool.submit(() -> retrieval.search(alice, "topic-leave", 5));
            awaitCalls(before + 4);
            long started = System.nanoTime();
            assertThatThrownBy(() -> retrieval.search(alice, "topic-leave", 5))
                    .isInstanceOfSatisfying(DocumentServiceException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            Thread.sleep(2500); // the four callers timed out, their model calls still hang
            assertThatThrownBy(() -> retrieval.search(alice, "topic-leave", 5)).as("slots stay taken")
                    .isInstanceOf(DocumentServiceException.class);
            assertThat(TestEmbeddingModel.INSTANCE.calls()).isEqualTo(before + 4);
            release.countDown();
        } finally {
            release.countDown();
        }
        TestEmbeddingModel.INSTANCE.reset();
        Thread.sleep(100);
        assertThat(retrieval.search(alice, "topic-leave", 5)).as("slots come back when the calls end").isNotEmpty();
    }

    /** Phase 8 (docs/capacity.md): a question that gives up interrupts its model call, so the request is cancelled. */
    @Test
    void search_whenTheEmbeddingTimesOut_interruptsTheModelCall() throws Exception {
        ready(alice, "topic-leave");
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch release = new CountDownLatch(1);
        TestEmbeddingModel.INSTANCE.onCall(() -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        try {
            assertThatThrownBy(() -> retrieval.search(alice, "topic-leave", 5)).isInstanceOf(DocumentServiceException.class);
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!interrupted.get() && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(interrupted).as("the abandoned call was interrupted").isTrue();
        } finally {
            release.countDown();
        }
    }

    /**
     * Phase 8 review RS1: a caller interrupted right after submitting cancels a task that never started; a cancelled
     * FutureTask never runs its body, so the caller must free the slot. Before the fix, four such calls took every
     * slot until a restart.
     */
    @Test
    void search_whenTheCallerIsInterrupted_keepsTheEmbeddingSlots() {
        ready(alice, "topic-leave");
        for (int i = 0; i < 8; i++) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> retrieval.search(alice, "topic-leave", 5)).isInstanceOf(RuntimeException.class);
            } finally {
                Thread.interrupted();
            }
        }
        assertThat(retrieval.search(alice, "topic-leave", 5)).as("all four slots are still free").isNotEmpty();
    }

    /** Waits for the model calls with a deadline: a lost slot must fail the test, not hang the build (review T2). */
    private static void awaitCalls(int calls) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (TestEmbeddingModel.INSTANCE.calls() < calls) {
            if (System.nanoTime() > deadline) throw new AssertionError("model calls: " + TestEmbeddingModel.INSTANCE.calls() + " < " + calls);
            Thread.sleep(20);
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (latch.getCount() > 0 && System.nanoTime() < deadline) {
            try {
                latch.await(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                // keeps hanging, like a model that does not notice the cancellation
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Phase 4 db review D4: with many foreign chunks, a filtered HNSW scan could return fewer rows than asked for. The
     * iterative scan keeps all of a small account's passages reachable.
     */
    @Test
    void search_whenTheAccountIsSmallAmongMany_stillReturnsAllItsPassages() {
        for (int i = 0; i < 40; i++) ready(bob, "topic-leave bob " + i);
        ready(alice, "topic-leave alice one");
        ready(alice, "topic-leave alice two");
        ready(alice, "topic-leave alice three");

        assertThat(retrieval.search(alice, "topic-leave", 5)).hasSize(3);
    }

    private UUID ready(String account, String text) {
        DocumentRow row = documents.insert(account, "test.pdf", 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(text));
        worker.runOnce();
        assertThat(documents.find(account, row.id()).orElseThrow().status().name()).isEqualTo("READY");
        return row.id();
    }
}
