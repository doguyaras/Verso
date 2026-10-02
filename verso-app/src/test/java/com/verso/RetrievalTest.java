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
import com.verso.support.VersoTestEnvironment;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
        DocumentRow pending = documents.insert(alice, "pending.pdf", 10, null, Instant.now());
        documents.insertFile(pending.id(), TestPdfs.pages("topic-leave pending"));
        UUID ready = ready(alice, "topic-leave ready");
        jdbc.update("UPDATE document.document SET status = 'PENDING', next_attempt_at = now() + interval '1 hour' "
                + "WHERE id = ?", ready);

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
    void search_whenChunksCameFromAnotherModel_failsClosed() {
        UUID id = ready(alice, "topic-leave old index");
        jdbc.update("UPDATE document.document_chunk SET embedding_model = 'old-model' WHERE document_id = ?", id);

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
