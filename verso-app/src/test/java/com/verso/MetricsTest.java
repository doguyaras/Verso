package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.TestChatModel;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * /actuator/prometheus (ADR-0014, reference 8.7): scraped without a token on the management port only, carries the
 * Verso metrics the alerts use, and no account, document id, file name, question or answer (llm-rules 2.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class MetricsTest {

    @Value("${local.server.port}")
    int port;

    @Value("${local.management.port}")
    int managementPort;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ModelCircuitBreaker circuit;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String account = "acct-metrics-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        TestEmbeddingModel.INSTANCE.reset();
        TestChatModel.INSTANCE.reset();
        circuit.reset();
        while (worker.isPaused()) Thread.sleep(50);
    }

    @Test
    void prometheus_whenScrapedWithoutToken_answersOnTheManagementPortOnly() throws Exception {
        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus", null);
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("verso_ingestion_queue", "verso_qa_questions_total", "jvm_memory_used_bytes");

        assertThat(get(port, "/actuator/prometheus", null).statusCode()).as("not on the API port").isIn(401, 404);
        assertThat(get(managementPort, "/actuator/info", null).statusCode()).as("other endpoints keep the token")
                .isEqualTo(401);
        assertThat(get(managementPort, "/actuator/env", TestIdp.bearer(account)).statusCode()).as("not exposed")
                .isEqualTo(404);
    }

    @Test
    void metrics_afterIngestionAndQuestions_countOutcomesAndCarryNoIdentifiersOrContent() throws Exception {
        DocumentRow pending = documents.insert(account, "MarkerFileName.pdf", 10, null, Instant.now());
        documents.insertFile(pending.id(), TestPdfs.pages("topic-leave MarkerPassage annual leave rules"));
        jdbc.update("UPDATE document.document SET next_attempt_at = now() - interval '2 minutes' WHERE id = ?", pending.id());
        Thread.sleep(5100); // the queue is read at most every 5 s
        String waiting = get(managementPort, "/actuator/prometheus", null).body();
        assertThat(value(waiting, "verso_ingestion_queue\\{status=\"pending\"\\}")).isEqualTo(1);
        assertThat(value(waiting, "verso_ingestion_oldest_due_wait_seconds")).isGreaterThanOrEqualTo(100);

        double readyBefore = value(waiting, "verso_ingestion_documents_total\\{outcome=\"ready\"\\}");
        worker.runOnce();
        TestChatModel.INSTANCE.answer("MarkerAnswer [1].");
        ask("topic-leave MarkerQuestion izin?");
        ask("topic-mars nothing relevant");

        String scrape = get(managementPort, "/actuator/prometheus", null).body();
        assertThat(value(scrape, "verso_ingestion_documents_total\\{outcome=\"ready\"\\}")).isEqualTo(readyBefore + 1);
        assertThat(value(scrape, "verso_qa_questions_total\\{outcome=\"answered\"\\}")).isGreaterThanOrEqualTo(1);
        assertThat(value(scrape, "verso_qa_questions_total\\{outcome=\"not_found\"\\}")).isGreaterThanOrEqualTo(1);
        assertThat(scrape).contains("verso_qa_chat_duration_seconds_bucket", "verso_qa_circuit_open", "verso_ingestion_paused");
        assertThat(scrape).doesNotContain(account, pending.id().toString(), "Marker");
    }

    /** Phase 7 review SP1/T1: the gauges the ModelUnavailable alert reads move with the outage, they are not NaN. */
    @Test
    void outageGauges_whenModelsFail_turnFromZeroToOne() throws Exception {
        String before = get(managementPort, "/actuator/prometheus", null).body();
        assertThat(value(before, "verso_ingestion_paused")).isEqualTo(0);
        assertThat(value(before, "verso_qa_circuit_open")).isEqualTo(0);

        DocumentRow doc = documents.insert(account, "x.pdf", 10, null, Instant.now());
        documents.insertFile(doc.id(), TestPdfs.pages("topic-leave rules"));
        TestEmbeddingModel.INSTANCE.failWith(new IllegalStateException("down"));
        worker.runOnce();
        assertThat(value(get(managementPort, "/actuator/prometheus", null).body(), "verso_ingestion_paused"))
                .as("paused after the embedding failure").isEqualTo(1);

        TestEmbeddingModel.INSTANCE.reset();
        circuit.recordFailure();
        circuit.recordFailure();
        String after = get(managementPort, "/actuator/prometheus", null).body();
        assertThat(value(after, "verso_qa_circuit_open")).isEqualTo(1);
        assertThat(value(after, "verso_ingestion_documents_total\\{outcome=\"released\"\\}")).isGreaterThanOrEqualTo(1);
        circuit.reset();
    }

    private HttpResponse<String> ask(String question) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/questions"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(int target, String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + target + path)).GET();
        if (bearer != null) request.header("Authorization", bearer);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static double value(String scrape, String series) {
        Matcher m = Pattern.compile("(?m)^" + series + " ([0-9.eE+-]+|NaN)$").matcher(scrape);
        assertThat(m.find()).as(series).isTrue();
        return Double.parseDouble(m.group(1));
    }
}
