package com.verso.document.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.repository.ContextIndexRepository;
import com.verso.document.repository.IngestionRepository;
import com.verso.document.worker.ContextHeaderBackfillJob;
import com.verso.document.worker.DoclingClient;
import com.verso.document.worker.DoclingParser;
import com.verso.document.worker.DocumentOutboxRelayJob;
import com.verso.document.worker.DocumentTextExtractor;
import com.verso.document.worker.DocxTextExtractor;
import com.verso.document.worker.IngestionMetrics;
import com.verso.document.worker.IngestionTransactionService;
import com.verso.document.worker.IngestionWorker;
import com.verso.document.worker.PageChunker;
import com.verso.document.worker.PdfTextExtractor;
import com.verso.document.worker.PlainTextExtractor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The process role switch (ADR-0039): with {@code verso.ingestion.enabled=false} none of the ingestion worker's beans
 * exist (an API-only process), without the key they do (one process does both, today's default). The outbox relay
 * does not follow the switch: every process writes outbox rows.
 */
class IngestionRoleTest {

    static final List<Class<?>> WORKER_BEANS = List.of(IngestionWorker.class, IngestionTransactionService.class,
            IngestionRepository.class, DocumentTextExtractor.class, PdfTextExtractor.class, DocxTextExtractor.class,
            PlainTextExtractor.class, DoclingParser.class, DoclingClient.class, PageChunker.class, IngestionMetrics.class,
            ContextIndexRepository.class, ContextHeaderBackfillJob.class);

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnIngestion
    static class Probe {
    }

    @Test
    void workerBeans_whenIngestionIsDisabled_areAbsent() {
        new WebApplicationContextRunner()
                .withPropertyValues("verso.ingestion.enabled=false")
                .withUserConfiguration(WORKER_BEANS.toArray(Class<?>[]::new))
                .withUserConfiguration(Probe.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    for (Class<?> type : WORKER_BEANS) assertThat(context).doesNotHaveBean(type);
                    assertThat(context).doesNotHaveBean(Probe.class);
                });
    }

    @Test
    void ingestionSwitch_whenNotSet_isOn() {
        new WebApplicationContextRunner().withUserConfiguration(Probe.class)
                .run(context -> assertThat(context).hasSingleBean(Probe.class));
        new WebApplicationContextRunner().withPropertyValues("verso.ingestion.enabled=true")
                .withUserConfiguration(Probe.class)
                .run(context -> assertThat(context).hasSingleBean(Probe.class));
    }

    @Test
    void everyWorkerBean_carriesTheSwitch_butNotTheOutboxRelay() {
        for (Class<?> type : WORKER_BEANS) {
            assertThat(type.isAnnotationPresent(ConditionalOnIngestion.class)).as(type.getSimpleName()).isTrue();
        }
        assertThat(DocumentOutboxRelayJob.class.isAnnotationPresent(ConditionalOnIngestion.class)).isFalse();
    }
}
