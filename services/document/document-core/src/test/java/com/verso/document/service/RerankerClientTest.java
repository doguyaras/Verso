package com.verso.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.verso.document.config.RerankerProperties;
import com.verso.document.service.RerankerClient.Failure;
import com.verso.document.service.RerankerClient.RerankException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.http.client.FilteredHostException;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0043: the TEI {@code /rerank} client against a fake sidecar: what is sent, the order read back, and every broken
 * answer (5xx, 4xx, wrong length, repeated or invented index, not JSON, oversized, slow, nobody listening) as a fixed
 * kind. Egress: internal addresses only, no redirect followed (llm-rules 1.1).
 */
class RerankerClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String answer = "[{\"index\": 2, \"score\": 0.9}, {\"index\": 0, \"score\": 0.5}, {\"index\": 1, \"score\": 0.1}]";
    private volatile long delayMs;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (exchange.getRequestURI().getPath().equals("/redirect")) {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    private RerankerClient client(String baseUrl, Duration timeout) {
        RerankerProperties properties = new RerankerProperties(true, URI.create(baseUrl), 20, Duration.ofMillis(500),
                timeout, 2000, 4, 3, Duration.ofMinutes(1));
        return new RerankerClient(properties, new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class),
                new StaticListableBeanFactory().getBeanProvider(HttpClientSettings.class));
    }

    private RerankerClient client() {
        return client("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2));
    }

    @Test
    void rank_postsTheQuestionAndTextsAndReturnsTheIndexesBestFirst() {
        List<Integer> order = client().rank("CAB toplantısı hangi gün?", List.of("bir", "iki", "üç"));

        assertThat(order).containsExactly(2, 0, 1);
        assertThat(paths).containsExactly("POST /rerank");
        JsonNode sent = JSON.readTree(bodies.getFirst());
        assertThat(sent.path("query").asString()).isEqualTo("CAB toplantısı hangi gün?");
        assertThat(sent.path("texts").size()).isEqualTo(3);
        assertThat(sent.path("texts").get(2).asString()).isEqualTo("üç");
        assertThat(sent.path("truncate").asBoolean()).isTrue();
        assertThat(sent.path("return_text").asBoolean()).as("no text comes back").isFalse();
    }

    @Test
    void rank_whenTheAnswerIsBroken_failsWithAFixedKind() {
        assertFailure(500, answer, Failure.UNAVAILABLE);
        assertFailure(429, answer, Failure.UNAVAILABLE);
        assertFailure(413, answer, Failure.INVALID_RESPONSE);
        assertFailure(200, "[{\"index\": 0}, {\"index\": 1}]", Failure.INVALID_RESPONSE);
        assertFailure(200, "[{\"index\": 0}, {\"index\": 0}, {\"index\": 1}]", Failure.INVALID_RESPONSE);
        assertFailure(200, "[{\"index\": 0}, {\"index\": 1}, {\"index\": 3}]", Failure.INVALID_RESPONSE);
        assertFailure(200, "[{\"index\": 0}, {\"index\": 1}, {\"index\": -1}]", Failure.INVALID_RESPONSE);
        assertFailure(200, "[{\"index\": \"0\"}, {\"index\": 1}, {\"index\": 2}]", Failure.INVALID_RESPONSE);
        assertFailure(200, "{\"error\": \"Gizli\"}", Failure.INVALID_RESPONSE);
        assertFailure(200, "not json", Failure.INVALID_RESPONSE);
        assertFailure(200, "[" + "{\"index\": 0},".repeat(10_000) + "{\"index\": 1}]", Failure.INVALID_RESPONSE);
    }

    @Test
    void rank_whenTheSidecarIsSlow_timesOut() {
        delayMs = 1500;
        RerankerClient slow = client("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofMillis(300));

        assertThatThrownBy(() -> slow.rank("q", List.of("a", "b", "c")))
                .isInstanceOfSatisfying(RerankException.class, e -> assertThat(e.failure()).isEqualTo(Failure.TIMEOUT));
    }

    @Test
    void rank_whenNothingListens_isUnavailable() {
        assertThatThrownBy(() -> client("http://127.0.0.1:1", Duration.ofSeconds(1)).rank("q", List.of("a", "b")))
                .isInstanceOfSatisfying(RerankException.class, e -> assertThat(e.failure()).isEqualTo(Failure.UNAVAILABLE));
    }

    @Test
    void rank_failureMessagesCarryNoText() {
        status = 500;
        answer = "{\"error\": \"Gizli pasaj\"}";
        assertThatThrownBy(() -> client().rank("Gizli soru", List.of("Gizli a", "Gizli b")))
                .isInstanceOf(RerankException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("Gizli");
                    assertThat(e.getCause()).isNull();
                });
    }

    /** llm-rules 1.1: the reranker's client reaches internal addresses only and never follows a redirect. */
    @Test
    void client_whenTheAddressIsPublicOrARedirectComes_doesNotGoThere() {
        RestClient rest = client().restClient();
        assertThatThrownBy(() -> rest.get().uri("http://203.0.113.9:80/health").retrieve().toBodilessEntity())
                .satisfiesAnyOf(e -> assertThat(e).isInstanceOf(FilteredHostException.class),
                        e -> assertThat(e).hasRootCauseInstanceOf(FilteredHostException.class));
        Integer redirected = rest.get().uri("/redirect").exchange((request, response) -> response.getStatusCode().value());
        assertThat(redirected).isEqualTo(302);
        assertThat(paths).doesNotContain("GET /elsewhere");
    }

    @Test
    void client_whenDisabled_hasNoHttpClient() {
        RerankerProperties off = new RerankerProperties(false, null, 20, Duration.ofMillis(500), Duration.ofSeconds(2),
                2000, 4, 3, Duration.ofMinutes(1));
        RerankerClient disabled = new RerankerClient(off, new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class),
                new StaticListableBeanFactory().getBeanProvider(HttpClientSettings.class));

        assertThat(disabled.restClient()).isNull();
        assertThatThrownBy(() -> disabled.rank("q", List.of("a", "b"))).isInstanceOf(IllegalStateException.class);
    }

    private void assertFailure(int status, String body, Failure expected) {
        this.status = status;
        this.answer = body;
        assertThatThrownBy(() -> client().rank("q", List.of("a", "b", "c")))
                .isInstanceOfSatisfying(RerankException.class, e -> assertThat(e.failure()).isEqualTo(expected));
    }
}
