package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.document.config.SystemModelProperties;
import com.verso.document.repository.SystemModelRepository;
import com.verso.document.repository.SystemModelRepository.ChunkRow;
import com.verso.document.systemmodel.SystemModelExtractor;
import com.verso.document.systemmodel.SystemModelPlan;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0054: a document another instance already finished is re-checked under the lock and not extracted again; a
 * document the extractor cannot handle gets an empty model, a database failure leaves it for the next round; a
 * document deleted meanwhile is left alone.
 */
class SystemModelBackfillJobTest {

    private final SystemModelRepository repository = mock(SystemModelRepository.class);
    private final SystemModelIndexer indexer = mock(SystemModelIndexer.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    @SuppressWarnings("unchecked")
    private SystemModelBackfillJob job() {
        when(transaction.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        Mockito.doAnswer(inv -> {
            ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(mock(TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());
        return new SystemModelBackfillJob(repository, indexer,
                new SystemModelProperties(false, 2, true, Duration.ofMinutes(1), 50), transaction,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void runOnce_whenTheClaimedDocumentWasFinishedByAnotherInstance_doesNotCountIt() {
        when(repository.lockNextPending(SystemModelExtractor.VERSION))
                .thenReturn(Optional.of(UUID.randomUUID()), Optional.of(UUID.randomUUID()), Optional.empty());
        when(indexer.index(any(), any())).thenReturn(SystemModelIndexer.Outcome.CURRENT,
                SystemModelIndexer.Outcome.EXTRACTED);

        assertThat(job().runOnce()).isEqualTo(1);
    }

    /** The extractor itself failed on the document: an empty model of this version, so the queue moves on. */
    @Test
    void runOnce_whenTheExtractorFails_marksTheDocumentEmpty() {
        UUID id = UUID.randomUUID();
        when(repository.lockNextPending(SystemModelExtractor.VERSION)).thenReturn(Optional.of(id), Optional.empty());
        when(indexer.index(any(), any())).thenThrow(new IllegalStateException("extractor"));

        assertThat(job().runOnce()).isEqualTo(1);

        verify(indexer).markEmpty(any(), any());
    }

    /** A database failure is not the document's fault: no empty model, the round ends, the next one retries. */
    @Test
    void runOnce_whenTheDatabaseFails_doesNotMarkTheDocumentEmpty() {
        UUID id = UUID.randomUUID();
        when(repository.lockNextPending(SystemModelExtractor.VERSION)).thenReturn(Optional.of(id), Optional.empty());
        when(indexer.index(any(), any())).thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(() -> job().runOnce()).isInstanceOf(DataAccessResourceFailureException.class);

        verify(indexer, never()).markEmpty(any(), any());
    }

    @Test
    void index_locksTheDocumentBeforeReadingItsState_andSkipsWhenCurrent() {
        SystemModelRepository repo = mock(SystemModelRepository.class);
        UUID id = UUID.randomUUID();
        List<ChunkRow> chunks = List.of();
        String digest = SystemModelPlan.digest(List.of());
        when(repo.lockDocument(id)).thenReturn(true);
        when(repo.chunks(id)).thenReturn(chunks);
        when(repo.state(id)).thenReturn(Optional.of(new SystemModelPlan.Stored(SystemModelExtractor.VERSION, digest)));

        var outcome = new SystemModelIndexer(repo, new SimpleMeterRegistry()).index(id, Instant.EPOCH);

        assertThat(outcome).isEqualTo(SystemModelIndexer.Outcome.CURRENT);
        InOrder order = inOrder(repo);
        order.verify(repo).lockDocument(id);
        order.verify(repo).state(id);
        verify(repo, never()).replace(any(), any(), any(), any(), anyInt(), any());
    }

    /** Deleted between READY and the extraction (a user delete, KVKK erasure): nothing to read, nothing to write. */
    @Test
    void index_whenTheDocumentIsGone_writesNothing() {
        SystemModelRepository repo = mock(SystemModelRepository.class);
        UUID id = UUID.randomUUID();
        when(repo.lockDocument(id)).thenReturn(false);
        SystemModelIndexer indexer = new SystemModelIndexer(repo, new SimpleMeterRegistry());

        assertThat(indexer.index(id, Instant.EPOCH)).isEqualTo(SystemModelIndexer.Outcome.GONE);
        indexer.markEmpty(id, Instant.EPOCH);

        verify(repo, never()).chunks(any());
        verify(repo, never()).replace(any(), any(), any(), any(), anyInt(), any());
    }
}
