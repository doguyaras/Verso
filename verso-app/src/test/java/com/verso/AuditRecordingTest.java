package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.platform.observability.tracing.TraceIds;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.AuditTables;
import com.verso.support.Multipart;
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
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the audit log records end to end (ADR-0026), with the real database and the real endpoints: every audited
 * operation leaves one row with its ids, outcome and the request's trace id; operations that did nothing (404, a
 * replayed upload) leave none; and no question, answer, file name, address or user agent reaches a row or a log line
 * (security-rules 4/5, llm-rules 2.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class AuditRecordingTest {

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
    private final String owner = "acct-audit-owner-" + UUID.randomUUID();
    private final String adminSub = "acct-audit-admin-" + UUID.randomUUID();
    private final String admin = "Bearer " + TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
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
        root.detachAppender(appender);
        appender.stop();
    }

    @Test
    void document_whenUploadedAndDeleted_leavesTwoRowsWithTheDocumentId() throws Exception {
        HttpResponse<String> uploaded = upload(owner, "MarkerFileName.pdf", null);
        assertThat(uploaded.statusCode()).isEqualTo(201);
        String document = JsonPath.read(uploaded.body(), "$.id");
        assertThat(send(owner, "DELETE", "/v1/documents/" + document, null).statusCode()).isEqualTo(204);

        assertThat(AuditTables.strings("select action || '/' || outcome || '/' || target_type || '/' || target_id "
                + "from audit.event where actor_sub = ? order by occurred_at, id", owner))
                .containsExactly("DOCUMENT_UPLOADED/SUCCESS/DOCUMENT/" + document,
                        "DOCUMENT_DELETED/SUCCESS/DOCUMENT/" + document);
        assertThat(AuditTables.strings("select trace_id from audit.event where actor_sub = ? and action = 'DOCUMENT_UPLOADED'",
                owner)).containsExactly(uploaded.headers().firstValue(TraceIds.HEADER).orElseThrow());
        assertThat(AuditTables.strings("select mode from audit.event where actor_sub = ?", owner))
                .containsOnly("local");
    }

    @Test
    void operations_whenNothingHappened_leaveNoRow() throws Exception {
        UUID key = UUID.randomUUID();
        assertThat(upload(owner, "a.pdf", key).statusCode()).isEqualTo(201);
        assertThat(upload(owner, "a.pdf", key).statusCode()).as("replay").isIn(200, 201);
        assertThat(send(owner, "DELETE", "/v1/documents/" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
        assertThat(send(owner, "DELETE", "/v1/conversations/" + UUID.randomUUID(), null).statusCode()).isEqualTo(404);
        assertThat(send(owner, "GET", "/v1/documents", null).statusCode()).isEqualTo(200);

        assertThat(AuditTables.strings("select action from audit.event where actor_sub = ?", owner))
                .as("one upload, no replay, no 404, no read").containsExactly("DOCUMENT_UPLOADED");
    }

    @Test
    void question_whenAnsweredRatedAndDeleted_recordsIdsOutcomeModelAndCitedDocuments() throws Exception {
        UUID document = ready(owner, "topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.answer("MarkerAnswerText [1].");

        HttpResponse<String> answer = send(owner, "POST", "/v1/questions",
                "{\"question\":\"topic-leave MarkerQuestionSalary?\"}");
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");
        assertThat(send(owner, "POST", "/v1/conversations/" + conversation + "/turns/" + turn + "/feedback",
                "{\"rating\":\"DOWN\",\"reason\":\"WRONG_SOURCE\",\"citationNumber\":1}").statusCode()).isEqualTo(200);
        assertThat(send(owner, "DELETE", "/v1/conversations/" + conversation, null).statusCode()).isEqualTo(204);
        send(owner, "POST", "/v1/questions", "{\"question\":\"topic-leave MarkerSecond?\"}");
        assertThat(send(owner, "DELETE", "/v1/conversations", null).statusCode()).isEqualTo(204);

        assertThat(AuditTables.strings("""
                select action || '/' || target_type || '/' || coalesce(target_id::text, '-') || '/'
                       || coalesce(conversation_id::text, '-') || '/' || coalesce(detail_code, '-') || '/'
                       || coalesce(model_id, '-') || '/' || mode || '/' || array_to_string(citation_document_ids, ',')
                from audit.event where actor_sub = ? and action in ('QUESTION_ASKED', 'FEEDBACK_GIVEN',
                     'CONVERSATION_DELETED') order by occurred_at, id
                """, owner)).hasSize(4).containsSubsequence(
                "QUESTION_ASKED/TURN/" + turn + "/" + conversation + "/ANSWERED/test-chat/local/" + document,
                "FEEDBACK_GIVEN/TURN/" + turn + "/" + conversation + "/DOWN/-/local/",
                "CONVERSATION_DELETED/CONVERSATION/" + conversation + "/-/-/-/local/");
        assertThat(AuditTables.rows(owner, "QUESTION_ASKED")).as("two questions").isEqualTo(2);
        assertThat(AuditTables.strings("select target_type || '/' || coalesce(target_id::text, '-') from audit.event "
                + "where actor_sub = ? and action = 'CONVERSATIONS_DELETED_ALL'", owner)).containsExactly("CONVERSATION/-");
        assertThat(AuditTables.strings("select trace_id from audit.event where actor_sub = ? and action = 'QUESTION_ASKED' "
                + "order by occurred_at, id limit 1", owner))
                .containsExactly(answer.headers().firstValue(TraceIds.HEADER).orElseThrow());
    }

    @Test
    void question_whenNotFound_isRecordedWithItsOutcomeAndNoCitations() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");

        HttpResponse<String> answer = send(owner, "POST", "/v1/questions", "{\"question\":\"topic-mars How many moons?\"}");

        assertThat((String) JsonPath.read(answer.body(), "$.outcome")).isEqualTo("NOT_FOUND");
        assertThat(AuditTables.strings("select detail_code || '/' || cardinality(citation_document_ids) from audit.event "
                + "where actor_sub = ? and action = 'QUESTION_ASKED'", owner)).containsExactly("NOT_FOUND/0");
    }

    @Test
    void adminActions_whenUsed_areRecordedWithTheAdminRole() throws Exception {
        LocalDate today = LocalDate.now();
        assertThat(send(admin, "GET", "/v1/admin/metrics?from=" + today.minusDays(1) + "&to=" + today.plusDays(1), null)
                .statusCode()).isEqualTo(200);
        assertThat(send(admin, "GET", "/v1/admin/audit/export?from=" + today.minusDays(1) + "&to=" + today.plusDays(1),
                null).statusCode()).isEqualTo(200);
        assertThat(send(owner, "GET", "/v1/admin/metrics?from=" + today + "&to=" + today, null).statusCode()).isEqualTo(403);

        assertThat(AuditTables.strings("select action || '/' || outcome || '/' || target_type from audit.event "
                + "where actor_sub = ? order by occurred_at, id", adminSub))
                .containsExactly("ADMIN_METRICS_VIEWED/SUCCESS/METRICS", "AUDIT_EXPORTED/SUCCESS/AUDIT_LOG");
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ? and 'verso-admin' = any(actor_roles)",
                adminSub)).isEqualTo(2);
        assertThat(AuditTables.strings("select action || '/' || outcome || '/' || target_type || '/' || detail_code "
                + "|| '/' || actor_roles::text from audit.event where actor_sub = ?", owner))
                .containsExactly("ACCESS_DENIED/DENIED/ADMIN_API/ADMIN_METRICS/{verso-user}");
    }

    /**
     * Security-rules 4/5: nothing that identifies content or the caller's machine reaches the table or any log line of
     * the audited paths. The markers stand for a question, an answer, a file name, a title; the address and the user
     * agent of the test client must not appear either.
     */
    @Test
    void auditedPaths_whenUsed_storeAndLogNoQuestionAnswerFileNameAddressOrUserAgent() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.answer("MarkerAnswerText [1].");
        HttpResponse<String> uploaded = upload(owner, "MarkerFileName.pdf", null);
        HttpResponse<String> answer = send(owner, "POST", "/v1/questions", "{\"question\":\"topic-leave MarkerQuestion?\"}");
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");
        send(owner, "PATCH", "/v1/conversations/" + conversation, "{\"title\":\"MarkerTitle\"}");
        send(owner, "POST", "/v1/conversations/" + conversation + "/turns/" + turn + "/feedback", "{\"rating\":\"UP\"}");
        send(owner, "DELETE", "/v1/documents/" + JsonPath.read(uploaded.body(), "$.id"), null);
        send(owner, "DELETE", "/v1/conversations", null);
        send(owner, "GET", "/v1/admin/audit?from=" + LocalDate.now() + "&to=" + LocalDate.now(), null);
        send(admin, "GET", "/v1/admin/audit/export?from=" + LocalDate.now().minusDays(1) + "&to=" + LocalDate.now().plusDays(1),
                null);

        assertThat(AuditTables.count("select count(*) from audit.event")).isGreaterThanOrEqualTo(7);
        String rows = String.join("\n", AuditTables.strings("select e::text from audit.event e"));
        assertThat(rows).doesNotContain("Marker").doesNotContain("127.0.0.1").doesNotContain("localhost")
                .doesNotContain("Java-http-client").doesNotContain(".pdf").doesNotContain("Annual leave");
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage() + event.getMDCPropertyMap()
                    + (event.getArgumentArray() == null ? "" : Stream.of(event.getArgumentArray()).map(String::valueOf)
                    .collect(Collectors.joining(" ")))
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            assertThat(text).as(event.getLoggerName()).doesNotContain("Marker").doesNotContain("127.0.0.1");
        }
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("Audit event not stored"));
    }

    private UUID ready(String account, String text) {
        DocumentRow row = documents.insert(account, "izin.pdf", DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(text));
        worker.runOnce();
        assertThat(documents.find(account, row.id()).orElseThrow().status().name()).isEqualTo("READY");
        return row.id();
    }

    private HttpResponse<String> upload(String account, String fileName, UUID key) throws Exception {
        Multipart body = Multipart.pdf(fileName, TestPdfs.pages("Lease text."));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/documents"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", body.contentType())
                .POST(body.body());
        if (key != null) request.header("X-Idempotency-Key", key.toString());
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String account, String method, String path, String body) throws Exception {
        String authorization = account.startsWith("Bearer ") ? account : TestIdp.bearer(account);
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
