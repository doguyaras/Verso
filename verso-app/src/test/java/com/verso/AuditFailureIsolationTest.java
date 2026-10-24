package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.AuditTables;
import com.verso.support.Multipart;
import com.verso.support.QaTables;
import com.verso.support.TestChatModel;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Failure isolation (ADR-0026): when the audit database is down, the audited requests still succeed, the lost rows are
 * counted (verso.audit.events.not.stored, which the alert AuditEventNotStored watches) and logged with the exception
 * type only; once the database is back, rows are written again. A real outage of the svc_audit login, not a mock: its
 * sessions are terminated and new logins refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.audit.datasource.connection-timeout=1s",
                "verso.audit.breaker-open=2s"})
@VersoTestEnvironment
class AuditFailureIsolationTest {

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

    @Autowired
    MeterRegistry meters;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String account = "acct-audit-down-" + UUID.randomUUID();
    private final String admin = "Bearer " + TestIdp.IDP.token().subject("acct-audit-down-admin").realmRoles("verso-admin").build();
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        QaTables.reset();
        AuditTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
        TestChatModel.INSTANCE.reset();
        circuit.reset();
        while (worker.isPaused()) Thread.sleep(50);
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        AuditTables.update("ALTER ROLE svc_audit LOGIN");
        root.detachAppender(appender);
        appender.stop();
    }

    @Test
    void requests_whenTheAuditDatabaseIsDown_stillSucceedAndTheLossIsCountedAndLoggedByTypeOnly() throws Exception {
        DocumentRow row = documents.insert(account, "izin.pdf", DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages("topic-leave Annual leave is twenty working days."));
        worker.runOnce();
        TestChatModel.INSTANCE.answer("Yirmi iş günü [1].");
        double failedBefore = notStored("failed");
        double openBefore = notStored("circuit_open");

        AuditTables.update("ALTER ROLE svc_audit NOLOGIN");
        AuditTables.count("select count(*) from (select pg_terminate_backend(pid) from pg_stat_activity "
                + "where usename = 'svc_audit') t");

        long started = System.nanoTime();
        HttpResponse<String> uploaded = upload();
        HttpResponse<String> answer = send(account, "POST", "/v1/questions", "{\"question\":\"topic-leave Yıllık izin?\"}");
        HttpResponse<String> deleted = send(account, "DELETE", "/v1/documents/" + JsonPath.read(uploaded.body(), "$.id"), null);
        HttpResponse<String> metrics = send(admin, "GET", "/v1/admin/metrics?from=" + LocalDate.now().minusDays(1) + "&to="
                + LocalDate.now().plusDays(1), null);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(uploaded.statusCode()).as("upload").isEqualTo(201);
        assertThat(answer.statusCode()).as("question").isEqualTo(200);
        assertThat((String) JsonPath.read(answer.body(), "$.outcome")).isEqualTo("ANSWERED");
        assertThat(deleted.statusCode()).as("delete").isEqualTo(204);
        assertThat(metrics.statusCode()).as("admin metrics").isEqualTo(200);
        // One 1 s connection wait, then the breaker skips the database: an outage does not cost a wait per request.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(15));

        assertThat(notStored("failed") - failedBefore).as("the first write failed").isGreaterThanOrEqualTo(1);
        assertThat(notStored("failed") + notStored("circuit_open") - failedBefore - openBefore)
                .as("every audited operation is counted as lost").isEqualTo(4);
        assertThat(appender.list).filteredOn(e -> e.getFormattedMessage().startsWith("Audit event not stored"))
                .isNotEmpty().allSatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(e.getFormattedMessage()).contains("code=AUDIT_WRITE_FAILED", "exceptionType=")
                            .doesNotContain("svc_audit").doesNotContain("password").doesNotContain("FATAL")
                            .doesNotContain("jdbc:");
                    assertThat(e.getThrowableProxy()).as("no stack trace, no exception message").isNull();
                });
        assertThat(AuditTables.count("select count(*) from audit.event")).isZero();

        // Back: the pool reconnects on its own (Hikari retries in the background) and rows are written again.
        AuditTables.update("ALTER ROLE svc_audit LOGIN");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (AuditTables.rows(account, "DOCUMENT_UPLOADED") == 0 && System.nanoTime() < deadline) {
            Thread.sleep(500);
            assertThat(upload().statusCode()).isEqualTo(201);
        }
        assertThat(AuditTables.rows(account, "DOCUMENT_UPLOADED")).as("recovers").isGreaterThanOrEqualTo(1);
    }

    private double notStored(String reason) {
        return meters.get("verso.audit.events.not.stored").tag("reason", reason).counter().count();
    }

    private HttpResponse<String> upload() throws Exception {
        Multipart body = Multipart.pdf("down.pdf", TestPdfs.pages("Lease text."));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/documents"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", body.contentType())
                .POST(body.body()).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String who, String method, String path, String body) throws Exception {
        String authorization = who.startsWith("Bearer ") ? who : TestIdp.bearer(who);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", authorization).timeout(Duration.ofSeconds(30));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
