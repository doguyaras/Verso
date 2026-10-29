package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.config.ContextHeaderProperties;
import com.verso.document.repository.IngestionRepository;
import com.verso.document.repository.IngestionRepository.Claim;
import com.verso.document.repository.IngestionRepository.StoredChunk;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.DocumentTextExtractor.Extraction;
import com.verso.document.worker.DocumentTextExtractor.Parser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * ADR-0061 in the ingestion worker: the embedding model reads header + chunk text, the stored chunk keeps its own text,
 * and the title (with the PDF metadata title, known only while the file exists) is stored with the pages.
 */
class IngestionWorkerContextTest {

    private static final String PAGE = """
            UZAKTAN EĞİTİM MERKEZİ (HUZEM) YÖNETMELİĞİ
            Müdür
            MADDE 7 – (1) Müdür üç yıl için görevlendirilir.""";

    private final IngestionTransactionService tx = mock(IngestionTransactionService.class);
    private final DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final Claim claim = new Claim(UUID.randomUUID(), "acct", DocumentFormat.PDF, UUID.randomUUID(), 1,
            "huzem.pdf");

    @Test
    @SuppressWarnings("unchecked")
    void process_embedsHeaderAndText_andStoresTheTextAlone() {
        when(tx.claimNext(any(), any())).thenReturn(Optional.of(claim), Optional.empty());
        when(tx.loadPages(claim)).thenReturn(List.of());
        when(tx.loadFile(claim)).thenReturn(Optional.of(new byte[]{1}));
        when(extractor.extract(eq(DocumentFormat.PDF), any(), any()))
                .thenReturn(new Extraction(List.of(PAGE), Parser.JDK, null, "Microsoft Word - huzem.docx"));
        when(embeddingModel.embed(anyList())).thenAnswer(inv -> {
            List<float[]> vectors = new ArrayList<>();
            for (Object ignored : (List<Object>) inv.getArgument(0)) vectors.add(new float[1024]);
            return vectors;
        });
        IngestionWorker worker = worker(true);

        assertThat(worker.runOnce()).isEqualTo(1);

        String title = "Uzaktan Eğitim Merkezi (HUZEM) Yönetmeliği";
        verify(tx).storePages(eq(claim), eq(List.of(PAGE)), eq(title), any());
        ArgumentCaptor<List<String>> inputs = ArgumentCaptor.forClass(List.class);
        verify(embeddingModel).embed(inputs.capture());
        // The chunk opens with the title line (part of the title, not a section) and reaches article 7 only after its
        // first third: the header is the title alone.
        assertThat(inputs.getValue()).singleElement().isEqualTo(title + "\n\n" + PAGE);
        ArgumentCaptor<List<StoredChunk>> stored = ArgumentCaptor.forClass(List.class);
        verify(tx).complete(eq(claim), eq(1), stored.capture(), anyString(), eq(ChunkContext.indexVersion(true)), any());
        assertThat(stored.getValue()).singleElement().satisfies(chunk -> {
            assertThat(chunk.content()).as("never the header: excerpts and citations read this").isEqualTo(PAGE);
            assertThat(chunk.contextHeader()).isEqualTo(title);
        });
    }

    /** embed-header=false: the vector is the text's alone; the header still goes to the full-text index. */
    @Test
    @SuppressWarnings("unchecked")
    void process_whenTheHeaderIsNotEmbedded_embedsTheTextAloneAndStillStoresTheHeader() {
        when(tx.claimNext(any(), any())).thenReturn(Optional.of(claim), Optional.empty());
        when(tx.loadPages(claim)).thenReturn(List.of(PAGE));
        when(tx.loadIndexTitle(claim)).thenReturn(Optional.of("Kept Title"));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[1024]));

        worker(false).runOnce();

        verify(embeddingModel).embed(List.of(PAGE));
        ArgumentCaptor<List<StoredChunk>> stored = ArgumentCaptor.forClass(List.class);
        verify(tx).complete(eq(claim), eq(1), stored.capture(), anyString(), eq(ChunkContext.indexVersion(false)),
                any());
        assertThat(stored.getValue()).singleElement()
                .satisfies(chunk -> assertThat(chunk.contextHeader()).startsWith("Kept Title"));
    }

    private IngestionWorker worker(boolean embedHeader) {
        IngestionWorker worker = new IngestionWorker(tx, extractor, new PageChunker(TestPdfs.properties()),
                embeddingModel, TestPdfs.properties(), Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"),
                ZoneOffset.UTC), new IngestionMetrics(mock(IngestionRepository.class), new SimpleMeterRegistry(),
                Clock.systemUTC()),
                new ContextHeaderProperties(embedHeader, true, Duration.ofSeconds(30), 5, Duration.ofMinutes(10)));
        worker.start();
        return worker;
    }

    /** A retried claim reads the title stored with the pages instead of computing it without the metadata title. */
    @Test
    void process_whenThePagesWereStoredEarlier_usesTheStoredTitle() {
        when(tx.claimNext(any(), any())).thenReturn(Optional.of(claim), Optional.empty());
        when(tx.loadPages(claim)).thenReturn(List.of("body text without a heading."));
        when(tx.loadIndexTitle(claim)).thenReturn(Optional.of("Kept Title"));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[1024]));

        worker(true).runOnce();

        verify(embeddingModel).embed(List.of("Kept Title\n\nbody text without a heading."));
    }
}
