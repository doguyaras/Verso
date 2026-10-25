package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.VersoTestEnvironment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0043 against the real database, with a stub reranker in place of the TEI sidecar: the fused candidates of the
 * reader's own documents go to the reranker after the query, its order decides the top-k, the passage the
 * answerability gate reads is the same as without the reranker, and a failing reranker leaves the fused order.
 */
@SpringBootTest
@VersoTestEnvironment
class RerankerRetrievalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpServer STUB = stub();
    private static final List<JsonNode> REQUESTS = new CopyOnWriteArrayList<>();
    private static volatile int status = 200;

    @DynamicPropertySource
    static void reranker(DynamicPropertyRegistry registry) {
        registry.add("verso.document.reranker.enabled", () -> "true");
        registry.add("verso.document.reranker.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
        registry.add("verso.document.reranker.candidates", () -> "20");
        // Failures in these tests must not open the breaker for the next one.
        registry.add("verso.document.reranker.circuit-failures", () -> "100");
    }

    /** Puts the texts carrying "rerank-first" first, then the others in reverse fused order. */
    private static HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/rerank", RerankerRetrievalTest::rerank);
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void rerank(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        REQUESTS.add(request);
        List<Integer> order = new ArrayList<>();
        JsonNode texts = request.path("texts");
        for (int i = 0; i < texts.size(); i++) if (texts.get(i).asString().contains("rerank-first")) order.add(i);
        for (int i = texts.size() - 1; i >= 0; i--) if (!order.contains(i)) order.add(i);
        StringBuilder body = new StringBuilder("[");
        for (int i = 0; i < order.size(); i++) {
            if (i > 0) body.append(',');
            body.append("{\"index\":").append(order.get(i)).append(",\"score\":").append(1.0 - i / 100.0).append('}');
        }
        byte[] out = (status == 200 ? body.append(']').toString() : "{\"error\":\"down\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Autowired
    DocumentRetrieval retrieval;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    private final String alice = "acct-alice-" + UUID.randomUUID();
    private final String bob = "acct-bob-" + UUID.randomUUID();

    @BeforeEach
    void clean() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        TestEmbeddingModel.INSTANCE.reset();
        REQUESTS.clear();
        status = 200;
        while (worker.isPaused()) Thread.sleep(50);
    }

    @Test
    void search_followsTheRerankersOrder_andKeepsTheGatesPassage() {
        for (int i = 1; i <= 5; i++) ready(alice, "Alice topic-leave passage " + i + ".");
        // Another topic: far from the question by cosine, so outside the fused top 3, but the reranker's first choice.
        ready(alice, "Alice topic-holiday passage rerank-first.");
        status = 500;
        List<RetrievedPassage> fused = retrieval.search(alice, "topic-leave how many days?", 3);
        status = 200;

        List<RetrievedPassage> reranked = retrieval.search(alice, "topic-leave how many days?", 3);

        assertThat(reranked).hasSize(3);
        assertThat(fused).noneSatisfy(p -> assertThat(p.content()).contains("rerank-first"));
        assertThat(reranked.getFirst().content()).as("the reranker's first choice leads").contains("rerank-first");
        assertThat(mostSimilar(reranked).passageId()).as("the gate reads the same passage (ADR-0022)")
                .isEqualTo(mostSimilar(fused).passageId());
        JsonNode sent = REQUESTS.getLast();
        assertThat(sent.path("query").asString()).isEqualTo("topic-leave how many days?");
        assertThat(sent.path("texts").size()).as("all fused candidates, up to the configured 20").isEqualTo(6);
    }

    @Test
    void search_whenTheRerankerFails_returnsTheFusedOrder() {
        for (int i = 1; i <= 6; i++) ready(alice, "Alice topic-leave passage " + i + ".");
        status = 500;

        List<RetrievedPassage> found = retrieval.search(alice, "topic-leave how many days?", 3);

        assertThat(REQUESTS).hasSize(1);
        JsonNode texts = REQUESTS.getFirst().path("texts");
        assertThat(found).hasSize(3);
        assertThat(found.stream().map(RetrievedPassage::content).toList()).as("the fused top 3, in fused order")
                .containsExactly(texts.get(0).asString(), texts.get(1).asString(), texts.get(2).asString());
    }

    /** llm-rules 4.1: only the reader's passages are ranked; another account's text never reaches the sidecar. */
    @Test
    void search_sendsOnlyTheReadersPassagesToTheReranker() {
        ready(alice, "Alice topic-leave policy: twenty days of annual leave.");
        for (int i = 0; i < 4; i++) ready(bob, "Bob topic-leave secret number " + i + ".");

        List<RetrievedPassage> found = retrieval.search(alice, "topic-leave how many days?", 5);

        assertThat(found).hasSize(1);
        assertThat(REQUESTS).as("a single candidate needs no rerank").isEmpty();
        ready(alice, "Alice topic-leave second page.");
        retrieval.search(alice, "topic-leave how many days?", 5);
        assertThat(REQUESTS).hasSize(1);
        assertThat(REQUESTS.getFirst().path("texts").toString()).doesNotContain("Bob");
    }

    private static RetrievedPassage mostSimilar(List<RetrievedPassage> passages) {
        return passages.stream().max(Comparator.comparingDouble(RetrievedPassage::similarity)).orElseThrow();
    }

    private UUID ready(String account, String text) {
        DocumentRow row = documents.insert(account, "test.pdf", DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(text));
        worker.runOnce();
        assertThat(documents.find(account, row.id()).orElseThrow().status().name()).isEqualTo("READY");
        return row.id();
    }
}
