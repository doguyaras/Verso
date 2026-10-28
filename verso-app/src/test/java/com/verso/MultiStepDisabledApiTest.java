package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.QaTables;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-0055 with its flag off (the default): a comparison question is answered exactly as before, with no
 * decomposition call, no new stage and no {@code reasoning} field value. Shares the default test context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class MultiStepDisabledApiTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ModelCircuitBreaker circuit;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String owner = "acct-multi-off-" + UUID.randomUUID();

    @BeforeEach
    void setUp() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        QaTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
        TestChatModel.INSTANCE.reset();
        circuit.reset();
        while (worker.isPaused()) Thread.sleep(50);
        DocumentRow row = documents.insert(owner, "izin.pdf", DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages("topic-leave Yillik izin 20 is gunudur."));
        worker.runOnce();
    }

    @Test
    void streamAndAsk_whenTheFlagIsOff_answerAComparisonInOnePassAsBefore() throws Exception {
        TestChatModel.INSTANCE.decompose(prompt -> "1. topic-leave yıllık izin kaç gün?\n2. topic-travel otel limiti?");
        TestChatModel.INSTANCE.answer("İzin 20 iş günüdür [1].");

        List<String[]> events = MultiStepApiTest.parse(http.send(request("/v1/questions/stream")
                .header("Accept", "text/event-stream, application/json").build(),
                HttpResponse.BodyHandlers.ofString()).body());
        HttpResponse<String> json = http.send(request("/v1/questions").build(), HttpResponse.BodyHandlers.ofString());

        assertThat(events.stream().filter(e -> e[0].equals("stage")).map(e -> (String) JsonPath.read(e[1], "$.stage")))
                .containsExactly("RETRIEVING", "PASSAGES_FOUND", "WRITING");
        assertThat(MultiStepApiTest.event(events, "done")).contains("\"reasoning\":null");
        assertThat(json.statusCode()).isEqualTo(200);
        assertThat(json.body()).contains("\"reasoning\":null");
        assertThat((String) JsonPath.read(json.body(), "$.outcome")).isEqualTo("ANSWERED");
        assertThat(TestChatModel.INSTANCE.decomposeCalls()).isZero();
        assertThat(MultiStepApiTest.text(TestChatModel.INSTANCE.lastPrompt())).doesNotContain("Alt soru");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", TestIdp.bearer(owner)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + MultiStepApiTest.QUESTION + "\"}"))
                .timeout(Duration.ofSeconds(30));
    }
}
