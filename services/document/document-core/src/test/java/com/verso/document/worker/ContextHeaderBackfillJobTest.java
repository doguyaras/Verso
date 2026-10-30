package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.document.config.ContextHeaderProperties;
import com.verso.document.repository.ContextIndexRepository;
import com.verso.document.repository.ContextIndexRepository.ChunkRow;
import com.verso.document.repository.ContextIndexRepository.Source;
import com.verso.document.repository.IngestionRepository;
import com.verso.document.repository.IngestionRepository.QueueStats;
import com.verso.document.repository.IngestionRepository.StoredChunk;
import com.verso.document.testing.TestPdfs;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0061: the backfill re-embeds header + text, stores the text unchanged under the same chunk ids, leaves a document
 * that changed meanwhile alone and stops on a model failure without holding the document.
 */
class ContextHeaderBackfillJobTest {

    private static final String PAGE = "DEPO YONETIMI PROSEDURU\nStok sayimi her ay yapilir.";

    private final ContextIndexRepository repository = mock(ContextIndexRepository.class);
    private final IngestionRepository ingestion = mock(IngestionRepository.class);
    private final IngestionWorker worker = mock(IngestionWorker.class);
    private final SystemModelIndexer systemModel = mock(SystemModelIndexer.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final UUID documentId = UUID.randomUUID();
    private final ChunkRow chunk = new ChunkRow(UUID.randomUUID(), 1, 0, "Stok sayimi her ay yapilir.");
    /** Open TransactionTemplate callbacks on this thread: the embedding call must see zero (reference 4.3). */
    private final AtomicInteger openTransactions = new AtomicInteger();
    private ContextHeaderBackfillJob job;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(transaction.execute(any())).thenAnswer(inv -> {
            openTransactions.incrementAndGet();
            try {
                return ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class));
            } finally {
                openTransactions.decrementAndGet();
            }
        });
        org.mockito.Mockito.doAnswer(inv -> {
            openTransactions.incrementAndGet();
            try {
                ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(mock(TransactionStatus.class));
            } finally {
                openTransactions.decrementAndGet();
            }
            return null;
        }).when(transaction).executeWithoutResult(any());
        when(ingestion.queueStats()).thenReturn(new QueueStats(0, 0, 0));
        when(repository.claimNext(eq(ChunkContext.indexVersion(true)), eq("test-embedding"), any(), any()))
                .thenReturn(Optional.of(documentId), Optional.empty());
        when(repository.load(documentId)).thenReturn(Optional.of(new Source("depo.pdf", null, List.of(PAGE),
                List.of(chunk))));
        job = new ContextHeaderBackfillJob(repository, ingestion, worker, systemModel, embeddingModel,
                TestPdfs.properties(), new ContextHeaderProperties(true, true, Duration.ofSeconds(30), 5,
                Duration.ofMinutes(10), Duration.ZERO),
                transaction, Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry());
        job.start();
    }

    @Test
    @SuppressWarnings("unchecked")
    void runOnce_embedsHeaderAndText_andStoresTheTextUnchangedUnderTheSameId() {
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[1024]));
        when(repository.replace(any(), anyInt(), anyString(), anyString(), anyList(), anyList())).thenReturn(true);

        assertThat(job.runOnce()).isEqualTo(1);

        ArgumentCaptor<List<String>> inputs = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel).embed(inputs.capture());
        assertThat(inputs.getValue()).containsExactly("Depo Yonetimi Proseduru\n\nStok sayimi her ay yapilir.");
        ArgumentCaptor<List<StoredChunk>> stored = ArgumentCaptor.forClass(List.class);
        verify(repository).replace(eq(documentId), eq(ChunkContext.indexVersion(true)), eq("test-embedding"),
                eq("Depo Yonetimi Proseduru"), eq(List.of(chunk.id())), stored.capture());
        assertThat(stored.getValue()).singleElement().satisfies(s -> {
            assertThat(s.content()).as("the excerpt source stays the chunk's own text")
                    .isEqualTo("Stok sayimi her ay yapilir.");
            assertThat(s.contextHeader()).isEqualTo("Depo Yonetimi Proseduru");
            assertThat(s.chunkIndex()).isZero();
        });
        verify(systemModel).index(eq(documentId), any());
    }

    /**
     * The whole point of the job's shape (reference 4.3): the embedding model is called between the transactions, never
     * inside one. TransactionBoundaryRulesTest cannot see TransactionTemplate callbacks, so this test does.
     */
    @Test
    void reindex_callsTheEmbeddingModelOutsideEveryTransaction() {
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            assertThat(openTransactions.get()).as("a TransactionTemplate callback is open around the embedding call")
                    .isZero();
            return List.of(new float[1024]);
        });
        when(repository.replace(any(), anyInt(), anyString(), anyString(), anyList(), anyList())).thenReturn(true);

        assertThat(job.runOnce()).isEqualTo(1);

        verify(embeddingModel).embed(anyList());
        assertThat(openTransactions.get()).as("every callback returned").isZero();
    }

    /** Deleted, re-ingested or finished by another instance meanwhile: not counted, the system model is not touched. */
    @Test
    void runOnce_whenTheDocumentChangedMeanwhile_skipsIt() {
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[1024]));
        when(repository.replace(any(), anyInt(), anyString(), anyString(), anyList(), anyList())).thenReturn(false);

        assertThat(job.runOnce()).isZero();
        verify(systemModel, never()).index(any(), any());
    }

    @Test
    void runOnce_whenTheModelFails_releasesTheLeaseAndStops() {
        when(embeddingModel.embed(anyList())).thenThrow(new IllegalStateException("down"));

        assertThat(job.runOnce()).isZero();
        verify(repository).releaseLease(documentId);
        verify(repository, never()).replace(any(), anyInt(), anyString(), anyString(), anyList(), anyList());
    }

    @Test
    void runOnce_whenTheModelAnswersAnotherDimension_writesNothing() {
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[768]));

        assertThat(job.runOnce()).isZero();
        verify(repository).releaseLease(documentId);
        verify(repository, never()).replace(any(), anyInt(), anyString(), anyString(), anyList(), anyList());
    }

    /** A stored title (from ingestion, with the PDF metadata) wins over the rules run again on the stored pages. */
    @Test
    void runOnce_usesTheStoredTitleWhenThereIsOne() {
        when(repository.load(documentId)).thenReturn(Optional.of(new Source("depo.pdf", "Depo Kilavuzu", List.of(PAGE),
                List.of(chunk))));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[1024]));
        when(repository.replace(any(), anyInt(), anyString(), anyString(), anyList(), anyList())).thenReturn(true);

        job.runOnce();

        verify(repository).replace(eq(documentId), anyInt(), anyString(), eq("Depo Kilavuzu"), anyList(), anyList());
    }

    /** New uploads first: nothing is claimed while documents wait for ingestion or the model circuit is open. */
    @Test
    void poll_whenUploadsWaitOrTheModelIsPaused_claimsNothing() {
        when(ingestion.queueStats()).thenReturn(new QueueStats(2, 0, 1));
        job.poll();
        when(ingestion.queueStats()).thenReturn(new QueueStats(0, 0, 0));
        when(worker.isPaused()).thenReturn(true);
        job.poll();

        verify(repository, never()).claimNext(anyInt(), anyString(), any(), any());
    }

    @Test
    void runOnce_afterShutdownBegan_claimsNothing() {
        job.stop();

        assertThat(job.runOnce()).isZero();
        verify(repository, never()).claimNext(anyInt(), anyString(), any(), any());
    }
}
