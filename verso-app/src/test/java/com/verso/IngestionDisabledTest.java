package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.outbox.DocumentOutbox;
import com.verso.document.repository.IngestionRepository;
import com.verso.document.worker.DoclingClient;
import com.verso.document.worker.DocumentOutboxRelayJob;
import com.verso.document.worker.DocumentTextExtractor;
import com.verso.document.worker.IngestionTransactionService;
import com.verso.document.worker.IngestionWorker;
import com.verso.document.worker.PageChunker;
import com.verso.support.Multipart;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The API-only process role (ADR-0039, verso.ingestion.enabled=false): the whole application starts without the
 * ingestion worker and its extractors, an upload is still accepted and queued (PENDING), and the outbox relay is
 * there, because this process writes audit records too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.ingestion.enabled=false"})
@VersoTestEnvironment
class IngestionDisabledTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void apiOnlyProcess_whenStarted_hasNoWorkerBeansButQueuesUploads() throws Exception {
        for (Class<?> type : new Class<?>[]{IngestionWorker.class, IngestionTransactionService.class,
                IngestionRepository.class, DocumentTextExtractor.class, PageChunker.class, DoclingClient.class}) {
            assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty();
        }
        assertThat(context.getBeanNamesForType(DocumentOutbox.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(DocumentOutboxRelayJob.class)).hasSize(1);

        Multipart body = Multipart.pdf("queued.pdf", com.verso.document.testing.TestPdfs.pages("Queued text."));
        HttpResponse<String> uploaded = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/documents"))
                        .header("Authorization", TestIdp.bearer("acct-api-only")).header("Content-Type", body.contentType())
                        .POST(body.body()).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(uploaded.statusCode()).isEqualTo(201);
        assertThat(uploaded.body()).contains("\"status\":\"PENDING\"");
    }
}
