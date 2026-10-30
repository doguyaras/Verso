package com.verso.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.verso.document.config.DocumentProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.RetrievalRepository;
import com.verso.document.service.PassageReranker;
import com.verso.document.service.SystemModelEnrichment;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The question embedding's bulkhead and timeout (ADR-0008; phase 5 review R2/R3): one error code for the client, but
 * the log reason tells overload from an outage, and a slot is given back after a timeout or a failure.
 */
class EmbeddingBulkheadTest {

    private static final float[] VECTOR = new float[] {0.1f, 0.2f};

    private final EmbeddingModel model = mock(EmbeddingModel.class);
    private final DocumentProperties properties = mock(DocumentProperties.class);

    private DocumentRetrievalServiceImpl service(int concurrency, Duration timeout) {
        when(properties.retrieval()).thenReturn(new DocumentProperties.Retrieval(timeout, concurrency, 20, 20, 60));
        when(properties.embeddingDimensions()).thenReturn(VECTOR.length);
        when(properties.embeddingModel()).thenReturn("bge-m3");
        return new DocumentRetrievalServiceImpl(mock(RetrievalRepository.class), model, properties,
                mock(TransactionTemplate.class), mock(PassageReranker.class), mock(SystemModelEnrichment.class));
    }

    private static String reason(Throwable t) {
        return ((DocumentServiceException) t).getSafeLogReason();
    }

    @Test
    void embed_whenEverySlotIsTaken_isBusyNotAnOutage() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch inside = new CountDownLatch(1);
        when(model.embed(anyString())).thenAnswer(invocation -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return VECTOR;
        });
        DocumentRetrievalServiceImpl service = service(1, Duration.ofSeconds(5));
        Thread first = Thread.ofVirtual().start(() -> service.embed("soru"));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.embed("ikinci soru"))
                .isInstanceOf(DocumentServiceException.class)
                .satisfies(t -> {
                    assertThat(((DocumentServiceException) t).getErrorCode()).isEqualTo(DocumentErrorCode.EMBEDDING_MODEL_UNAVAILABLE);
                    assertThat(reason(t)).isEqualTo("EMBEDDING_BUSY");
                });
        release.countDown();
        first.join(5_000);
        // The slot is free again once the first call ends.
        assertThat(service.embed("üçüncü soru").vector()).isEqualTo(VECTOR);
    }

    @Test
    void embed_whenTheModelDoesNotAnswerInTime_timesOutAndGivesTheSlotBack() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(model.embed(anyString())).thenAnswer(invocation -> {
            release.await(5, TimeUnit.SECONDS);
            return VECTOR;
        });
        DocumentRetrievalServiceImpl service = service(1, Duration.ofMillis(100));
        assertThatThrownBy(() -> service.embed("soru")).satisfies(t -> assertThat(reason(t)).isEqualTo("EMBEDDING_TIMEOUT"));
        release.countDown();
        // The hanging call keeps its slot until it really ends (it has now): the next call gets it.
        Thread.sleep(200);
        when(model.embed(anyString())).thenReturn(VECTOR);
        assertThat(service.embed("soru").vector()).isEqualTo(VECTOR);
    }

    @Test
    void embed_whenTheModelFails_isAFailureAndGivesTheSlotBack() {
        when(model.embed(anyString())).thenThrow(new IllegalStateException("connection refused"));
        DocumentRetrievalServiceImpl service = service(1, Duration.ofSeconds(5));
        assertThatThrownBy(() -> service.embed("soru")).satisfies(t -> assertThat(reason(t)).isEqualTo("EMBEDDING_FAILED"));
        doReturn(VECTOR).when(model).embed(anyString());
        assertThat(service.embed("soru").vector()).isEqualTo(VECTOR);
    }

    @Test
    void embed_whenTheVectorHasAnotherShape_saysSo() {
        when(model.embed(anyString())).thenReturn(new float[] {1f, 2f, 3f});
        DocumentRetrievalServiceImpl service = service(1, Duration.ofSeconds(5));
        assertThatThrownBy(() -> service.embed("soru")).satisfies(t -> assertThat(reason(t)).isEqualTo("EMBEDDING_WRONG_DIMENSIONS"));
    }
}
