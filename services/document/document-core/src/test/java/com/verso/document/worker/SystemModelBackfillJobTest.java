package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR-0054: a document another instance already finished is re-checked under the lock and not extracted again. */
class SystemModelBackfillJobTest {

    private final SystemModelRepository repository = mock(SystemModelRepository.class);
    private final SystemModelIndexer indexer = mock(SystemModelIndexer.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);

    @SuppressWarnings("unchecked")
    private SystemModelBackfillJob job() {
        when(transaction.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
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

    @Test
    void index_locksTheDocumentBeforeReadingItsState_andSkipsWhenCurrent() {
        SystemModelRepository repo = mock(SystemModelRepository.class);
        UUID id = UUID.randomUUID();
        List<ChunkRow> chunks = List.of();
        String digest = SystemModelPlan.digest(List.of());
        when(repo.chunks(id)).thenReturn(chunks);
        when(repo.state(id)).thenReturn(Optional.of(new SystemModelPlan.Stored(SystemModelExtractor.VERSION, digest)));

        var outcome = new SystemModelIndexer(repo, new SimpleMeterRegistry()).index(id, Instant.EPOCH);

        assertThat(outcome).isEqualTo(SystemModelIndexer.Outcome.CURRENT);
        InOrder order = inOrder(repo);
        order.verify(repo).lockDocument(id);
        order.verify(repo).state(id);
        verify(repo, never()).replace(any(), any(), any(), any(), anyInt(), any());
    }
}
