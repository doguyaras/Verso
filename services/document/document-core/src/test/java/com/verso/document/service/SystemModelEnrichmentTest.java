package com.verso.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.verso.document.api.enums.SourceUnit;
import com.verso.document.config.SystemModelProperties;
import com.verso.document.repository.RetrievalRepository;
import com.verso.document.repository.RetrievalRepository.Candidate;
import com.verso.document.repository.SystemModelRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-0054: the enrichment is off by default; when on it adds at most the bound, after the fused candidates. */
class SystemModelEnrichmentTest {

    private final SystemModelRepository systemModel = mock(SystemModelRepository.class);
    private final RetrievalRepository retrieval = mock(RetrievalRepository.class);
    private static final String[] PRINCIPALS = {"user:alice", "group:/depo"};

    private SystemModelEnrichment enrichment(boolean enabled) {
        return new SystemModelEnrichment(systemModel, retrieval,
                new SystemModelProperties(enabled, 2, true, Duration.ofMinutes(1), 50), new SimpleMeterRegistry());
    }

    private static Candidate candidate(UUID id) {
        return new Candidate(id, UUID.randomUUID(), "a.pdf", 1, SourceUnit.PAGE, "text", 0.5, null, null);
    }

    @Test
    void extra_whenOff_readsNothing() {
        assertThat(enrichment(false).extra(PRINCIPALS, "m", new float[]{1}, "WMS-204 nedir", List.of())).isEmpty();
        verifyNoInteractions(systemModel, retrieval);
    }

    @Test
    void extra_whenOn_asksForDefiningChunksWithTheReadersPrincipalsBoundedAndWithoutTheFoundOnes() {
        UUID found = UUID.randomUUID();
        UUID defining = UUID.randomUUID();
        when(systemModel.definingChunks(any(), any(), anyList(), anyList(), anyInt())).thenReturn(List.of(defining));
        when(retrieval.byIds(any(), any(), any(), anyList())).thenReturn(List.of(candidate(defining)));

        List<Candidate> extra = enrichment(true).extra(PRINCIPALS, "m", new float[]{1}, "wms-204 nedir",
                List.of(candidate(found)));

        assertThat(extra).extracting(Candidate::chunkId).containsExactly(defining);
        verify(systemModel).definingChunks(eq(PRINCIPALS), eq("m"),
                org.mockito.ArgumentMatchers.argThat(keys -> keys.contains("WMS-204")), eq(List.of(found)), eq(2));
        verify(retrieval).byIds(eq(PRINCIPALS), eq("m"), any(), eq(List.of(defining)));
    }
}
