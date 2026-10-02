package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.Multipart;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Per-account limits of the upload path with small values (ADR-0011): the document quota (409) and the queue share
 * (429, security review S3), both counted under a per-account lock so parallel uploads cannot overrun them (C6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@VersoTestEnvironment
@TestPropertySource(properties = {"verso.document.max-documents-per-account=3", "verso.document.max-queued-per-account=2"})
class DocumentLimitsTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestionWorker worker;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String account = "acct-limits-" + UUID.randomUUID();

    @BeforeEach
    void clean() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        TestEmbeddingModel.INSTANCE.reset();
        while (worker.isPaused()) Thread.sleep(50);
    }

    @Test
    void upload_whenQueueOrQuotaIsFull_isRejectedUntilItFreesUp() throws Exception {
        assertThat(upload("a").statusCode()).isEqualTo(201);
        assertThat(upload("b").statusCode()).isEqualTo(201);
        HttpResponse<String> queued = upload("c");
        assertThat(queued.statusCode()).as("two waiting already").isEqualTo(429);
        assertThat(queued.body()).contains("\"code\":10015");

        assertThat(worker.runOnce()).isEqualTo(2);
        assertThat(upload("c").statusCode()).as("the queue drained").isEqualTo(201);
        HttpResponse<String> quota = upload("d");
        assertThat(quota.statusCode()).as("three documents in total").isEqualTo(409);
        assertThat(quota.body()).contains("\"code\":10013");
    }

    /** Review C6: parallel uploads are counted one after the other; the quota is never exceeded. */
    @Test
    void upload_whenSentInParallel_neverExceedsTheLimits() throws Exception {
        int parallel = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> uploads = new ArrayList<>();
        for (int i = 0; i < parallel; i++) {
            String text = "parallel " + i;
            uploads.add(() -> {
                start.await();
                return upload(text).statusCode();
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(parallel)) {
            List<Future<Integer>> results = uploads.stream().map(pool::submit).toList();
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> result : results) codes.add(result.get());
            assertThat(codes).filteredOn(c -> c == 201).hasSize(2);
            assertThat(codes).allSatisfy(c -> assertThat(c).isIn(201, 429, 503));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document WHERE account_id = ?", Integer.class,
                account)).isEqualTo(2);
    }

    private HttpResponse<String> upload(String text) throws Exception {
        Multipart body = Multipart.pdf("limits.pdf", TestPdfs.pages(text));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/documents"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", body.contentType())
                .POST(body.body()).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
