package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.verso.document.config.DoclingProperties;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.DoclingClient.DoclingException;
import com.verso.document.worker.DoclingClient.Failure;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.http.client.FilteredHostException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * ADR-0032: the docling-serve client against a fake sidecar: the asynchronous submit / poll / result flow and what is
 * sent (fixed file name, input format, Tesseract tur + eng, no images, no callbacks), and every failure as a fixed kind
 * (5xx, task failure, timeout, oversized or malformed result, unsafe task id, redirect, shutdown, public address).
 */
class DoclingClientTest {

    private static final String DOCUMENT = "{\"schema_name\": \"DoclingDocument\", \"body\": {\"children\": []}, \"pages\": {}}";

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final AtomicInteger polls = new AtomicInteger();
    private volatile int submitStatus = 200;
    private volatile String taskId = "6f1c2a9e-0d2b-4b8e-9c55-0a1b2c3d4e5f";
    private volatile int pendingPolls = 1;
    private volatile String finalStatus = "success";
    private volatile String result = "{\"status\": \"success\", \"document\": {\"json_content\": " + DOCUMENT + "}}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(exchange.getRequestMethod() + " " + path);
        byte[] in = exchange.getRequestBody().readAllBytes();
        bodies.put(path, new String(in, StandardCharsets.ISO_8859_1));
        if (path.equals("/v1/convert/file/async")) {
            respond(exchange, submitStatus, "{\"task_id\": \"" + taskId + "\", \"task_status\": \"pending\"}");
        } else if (path.startsWith("/v1/status/poll/")) {
            boolean done = polls.incrementAndGet() > pendingPolls;
            respond(exchange, 200, "{\"task_status\": \"" + (done ? finalStatus : "started") + "\"}");
        } else if (path.startsWith("/v1/result/")) {
            respond(exchange, 200, result);
        } else if (path.equals("/redirect")) {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            respond(exchange, 302, "");
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private DoclingClient client(Duration documentTimeout) {
        return client(TestPdfs.docling(true, "http://127.0.0.1:" + server.getAddress().getPort(), documentTimeout,
                Duration.ofMillis(20), 3));
    }

    private static DoclingClient client(DoclingProperties properties) {
        StaticListableBeanFactory none = new StaticListableBeanFactory();
        return new DoclingClient(properties, none.getBeanProvider(RestClient.Builder.class),
                none.getBeanProvider(org.springframework.boot.http.client.HttpClientSettings.class));
    }

    @Test
    void convert_whenTheSidecarSucceeds_pollsUntilDoneAndReturnsTheDocument() {
        JsonNode document = client(Duration.ofSeconds(5)).convert("%PDF-1.7 bytes".getBytes(StandardCharsets.UTF_8), "pdf", () -> false);

        assertThat(document.path("schema_name").asString()).isEqualTo("DoclingDocument");
        assertThat(requests).first().isEqualTo("POST /v1/convert/file/async");
        assertThat(requests).last().isEqualTo("GET /v1/result/" + taskId);
        assertThat(polls.get()).isEqualTo(2);
        String form = bodies.get("/v1/convert/file/async");
        assertThat(form).contains("filename=\"document.pdf\"", "%PDF-1.7 bytes")
                .containsPattern("(?s)name=\"from_formats\".{0,200}?\\R\\Rpdf\\R")
                .contains("name=\"ocr_lang\"", "tur", "eng", "name=\"ocr_preset\"", "tesserocr")
                .contains("name=\"include_images\"", "name=\"document_timeout\"")
                .doesNotContain("callbacks", "http_sources");
    }

    @Test
    void convert_whenTheSidecarFailsOrMisbehaves_failsWithAFixedKind() {
        submitStatus = 503;
        assertFailure(Duration.ofSeconds(5), Failure.UNAVAILABLE);
        submitStatus = 200;

        finalStatus = "failure";
        assertFailure(Duration.ofSeconds(5), Failure.CONVERSION_FAILED);
        finalStatus = "success";

        result = "{\"status\": \"partial_success\", \"document\": {\"json_content\": " + DOCUMENT + "}}";
        assertFailure(Duration.ofSeconds(5), Failure.CONVERSION_FAILED);
        result = "{\"status\": \"success\", \"document\": {\"json_content\": null}}";
        assertFailure(Duration.ofSeconds(5), Failure.INVALID_RESPONSE);
        result = "not json";
        assertFailure(Duration.ofSeconds(5), Failure.INVALID_RESPONSE);

        // A task id is never put into a URL unless it looks like one.
        taskId = "..%2F..%2Fadmin";
        assertFailure(Duration.ofSeconds(5), Failure.INVALID_RESPONSE);
        taskId = "6f1c2a9e-0d2b-4b8e-9c55-0a1b2c3d4e5f";

        // Still converting when the document timeout is over.
        pendingPolls = Integer.MAX_VALUE;
        assertFailure(Duration.ofMillis(200), Failure.TIMEOUT);
    }

    @Test
    void convert_whenTheResultIsLargerThanTheLimit_isRefusedWhileReading() {
        result = "{\"status\": \"success\", \"pad\": \"" + "x".repeat(40 * 1024 * 1024) + "\"}";
        assertFailure(Duration.ofSeconds(10), Failure.TOO_LARGE);
    }

    @Test
    void convert_whenShutdownBeginsWhileConverting_stopsPolling() {
        pendingPolls = Integer.MAX_VALUE;
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).convert(new byte[]{1}, "pdf", () -> polls.get() >= 2))
                .isInstanceOfSatisfying(DoclingException.class, e -> assertThat(e.failure()).isEqualTo(Failure.CANCELLED));
        assertThat(polls.get()).isEqualTo(2);
    }

    @Test
    void convert_whenNothingListens_isUnavailable() {
        DoclingClient client = client(TestPdfs.docling(true, "http://127.0.0.1:1", Duration.ofSeconds(5),
                Duration.ofMillis(20), 3));
        assertThatThrownBy(() -> client.convert(new byte[]{1}, "pdf", () -> false))
                .isInstanceOfSatisfying(DoclingException.class, e -> assertThat(e.failure()).isEqualTo(Failure.UNAVAILABLE));
    }

    /** llm-rules 1.1: the sidecar's client reaches internal addresses only and never follows a redirect. */
    @Test
    void client_whenTheAddressIsPublicOrARedirectComes_doesNotGoThere() {
        RestClient rest = client(Duration.ofSeconds(5)).restClient();
        assertThatThrownBy(() -> rest.get().uri("http://203.0.113.9:5001/health").retrieve().toBodilessEntity())
                .satisfiesAnyOf(e -> assertThat(e).isInstanceOf(FilteredHostException.class),
                        e -> assertThat(e).hasRootCauseInstanceOf(FilteredHostException.class));
        Integer status = rest.get().uri("/redirect").exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).isEqualTo(302);
        assertThat(requests).doesNotContain("GET /elsewhere");
    }

    @Test
    void readLimited_whenTheStreamIsLonger_failsAtTheLimit() throws IOException {
        assertThat(DoclingClient.readLimited(new java.io.ByteArrayInputStream(new byte[10]), 10)).hasSize(10);
        assertThatThrownBy(() -> DoclingClient.readLimited(new java.io.ByteArrayInputStream(new byte[11]), 10))
                .isInstanceOfSatisfying(DoclingException.class, e -> assertThat(e.failure()).isEqualTo(Failure.TOO_LARGE));
    }

    private void assertFailure(Duration timeout, Failure expected) {
        polls.set(0);
        assertThatThrownBy(() -> client(timeout).convert(new byte[]{1}, "pdf", () -> false))
                .isInstanceOfSatisfying(DoclingException.class, e -> {
                    assertThat(e.failure()).isEqualTo(expected);
                    assertThat(e.getCause()).as("no cause: it could carry document text").isNull();
                });
    }
}
