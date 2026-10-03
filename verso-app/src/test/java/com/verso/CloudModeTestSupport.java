package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.FakeModelServer;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared steps of the cloud mode tests: a READY document, a question over HTTP, a log capture. */
abstract class CloudModeTestSupport {

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

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final String account = "acct-cloud-" + UUID.randomUUID();
    Logger root;
    ListAppender<ILoggingEvent> appender;

    abstract FakeModelServer provider();

    @BeforeEach
    void setUpCloud() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        TestEmbeddingModel.INSTANCE.reset();
        provider().reset();
        circuit.reset();
        while (worker.isPaused()) Thread.sleep(50);
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDownCloud() {
        root.detachAppender(appender);
        appender.stop();
    }

    UUID ready(String fileName, String text) {
        DocumentRow row = documents.insert(account, fileName, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(text));
        worker.runOnce();
        assertThat(documents.find(account, row.id()).orElseThrow().status().name()).isEqualTo("READY");
        return row.id();
    }

    HttpResponse<String> ask(String question) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/questions"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question + "\"}"))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> info() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/info"))
                .header("Authorization", TestIdp.bearer(account)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    void assertNoLogContains(String... markers) {
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            for (String marker : markers) assertThat(text).as(event.getLoggerName()).doesNotContain(marker);
        }
    }
}
