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
import com.verso.qa.api.QaSubjects;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.AuditTables;
import com.verso.support.QaTables;
import com.verso.support.TestChatModel;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * KVKK data-subject requests and complete document deletion (ADR-0034) over HTTP with the real database: a deleted
 * document leaves no row keyed to it outside the references kept on purpose; the export holds exactly the subject's
 * data; the erasure removes it, is idempotent and safe when fired twice at once, keeps collection documents and audit
 * rows and reports them; everything is verso-admin only; the erasure needs its confirmation; no content in the log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class SubjectRequestApiTest {

    /** Columns that may still name a deleted document, on purpose (ADR-0023, ADR-0026, ADR-0034). */
    private static final List<String> KEPT_REFERENCES = List.of(
            "qa.turn_citation.document_id",          // history shows the citation as "deleted"
            "qa.usage_daily_document.document_id",   // anonymous daily counter
            "audit.event.target_id",                 // append-only, kept for its retention
            "audit.event.citation_document_ids");

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
    private final String subject = "acct-subj-a-" + UUID.randomUUID();
    private final String other = "acct-subj-b-" + UUID.randomUUID();
    private final String adminSub = "acct-subj-admin-" + UUID.randomUUID();
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

    /** Goal 1: every schema is scanned, so a future table keyed to documents cannot be forgotten silently. */
    @Test
    void documentDelete_whenDone_leavesNoRowKeyedToTheDocumentOutsideTheKeptReferences() throws Exception {
        UUID document = ready(subject, "topic-leave Annual leave is twenty working days.", "izin-ayse.pdf");
        TestChatModel.INSTANCE.answer("Yirmi iş günü [1].");
        HttpResponse<String> answer = ask(subject, "topic-leave Yıllık izin kaç gün?");
        assertThat(answer.statusCode()).isEqualTo(200);
        rate(subject, answer, "{\"rating\":\"UP\"}");
        Map<String, Long> before = rowsNaming(document);
        assertThat(before).as("before the delete").containsKeys("document.document.id",
                "document.document_page.document_id", "document.document_chunk.document_id",
                "document.document_principal.document_id");
        // The original file is removed once ingestion succeeded (IngestionRepository); nothing to find there.
        assertThat(before).doesNotContainKey("document.document_file.document_id");

        assertThat(send(TestIdp.bearer(subject), "DELETE", "/v1/documents/" + document, null).statusCode()).isEqualTo(204);

        Map<String, Long> left = rowsNaming(document);
        assertThat(left.keySet()).as("only the references kept on purpose").isSubsetOf(KEPT_REFERENCES);
        assertThat(left).containsKey("qa.turn_citation.document_id");
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ? and answer = ?", subject,
                "Yirmi iş günü [1].")).as("the answer text stays until redacted").isOne();
    }

    /** Goal 1, purge option: the answers that quoted a deleted document get the fixed notice; counters stay. */
    @Test
    void redaction_whenTheDocumentIsDeleted_replacesTheAnswersOfEveryAccountAndIsIdempotent() throws Exception {
        UUID document = ready(subject, "topic-leave Annual leave is twenty working days.", "izin.pdf");
        TestChatModel.INSTANCE.answer("Ayşe Yılmaz yirmi gün izinli [1].");
        HttpResponse<String> asked = ask(subject, "topic-leave Yıllık izin kaç gün?");
        String conversation = JsonPath.read(asked.body(), "$.conversationId");
        long counted = QaTables.count("select coalesce(sum(citations), 0) from qa.usage_daily_document where document_id = ?",
                document);
        String path = "/v1/admin/documents/" + document + "/answer-redaction";
        String body = "{\"confirm\":\"" + document + "\"}";

        HttpResponse<String> early = send(admin, "POST", path, body);
        assertThat(early.statusCode()).as("document still exists").isEqualTo(409);
        assertThat(early.body()).contains("\"code\":13021");
        assertThat(send(admin, "POST", path, "{\"confirm\":\"" + UUID.randomUUID() + "\"}").body())
                .contains("\"code\":13020");
        assertThat(send(admin, "POST", path, "{}").statusCode()).isEqualTo(400);
        assertThat(send(admin, "POST", "/v1/admin/documents/not-a-uuid/answer-redaction", body).statusCode())
                .as("path binding").isEqualTo(400);
        assertThat(send(TestIdp.bearer(subject), "DELETE", "/v1/documents/" + document, null).statusCode()).isEqualTo(204);
        assertThat(send(TestIdp.bearer(other), "POST", path, body).statusCode()).as("not an admin").isEqualTo(403);

        HttpResponse<String> redacted = send(admin, "POST", path, body);

        assertThat(redacted.statusCode()).isEqualTo(200);
        assertThat(redacted.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat((Integer) JsonPath.read(redacted.body(), "$.answersRedacted")).isOne();
        HttpResponse<String> history = send(TestIdp.bearer(subject), "GET", "/v1/conversations/" + conversation, null);
        assertThat((String) JsonPath.read(history.body(), "$.turns[0].answer")).isEqualTo(QaSubjects.REDACTED_ANSWER);
        assertThat((String) JsonPath.read(history.body(), "$.turns[0].question")).isEqualTo("topic-leave Yıllık izin kaç gün?");
        assertThat((Boolean) JsonPath.read(history.body(), "$.turns[0].citations[0].documentDeleted")).isTrue();
        assertThat(QaTables.count("select coalesce(sum(citations), 0) from qa.usage_daily_document where document_id = ?",
                document)).as("counters stay").isEqualTo(counted);
        assertThat((Integer) JsonPath.read(send(admin, "POST", path, body).body(), "$.answersRedacted")).as("repeat").isZero();
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ? and action = 'ANSWERS_REDACTED' "
                + "and target_id = ?", adminSub, document)).isEqualTo(2);
        assertNoContentLogged("Ayşe Yılmaz", "izin.pdf");
    }

    /** Goal 2, access: the export holds the subject's history, documents and audit ids, and nothing of anyone else. */
    @Test
    void export_whenAdmin_holdsExactlyTheSubjectsDataWithNoStore() throws Exception {
        ready(subject, "topic-leave Annual leave is twenty working days.", "izin-a.pdf");
        ready(other, "topic-leave Annual leave is twenty working days.", "izin-b.pdf");
        TestChatModel.INSTANCE.answer("Yirmi iş günü [1].");
        HttpResponse<String> mine = ask(subject, "topic-leave A sorusu?");
        rate(subject, mine, "{\"rating\":\"DOWN\",\"reason\":\"OUTDATED\"}");
        ask(other, "topic-leave B sorusu?");

        HttpResponse<String> summary = send(admin, "POST", "/v1/admin/subjects/summary", subjectBody(subject));
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(summary.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat((Integer) JsonPath.read(summary.body(), "$.history.conversations")).isOne();
        assertThat((Integer) JsonPath.read(summary.body(), "$.history.feedback")).isOne();
        assertThat((Integer) JsonPath.read(summary.body(), "$.history.activeDays")).isOne();
        assertThat((Integer) JsonPath.read(summary.body(), "$.documents.personalCount")).isOne();
        assertThat((Integer) JsonPath.read(summary.body(), "$.auditEventsAsActor")).isGreaterThanOrEqualTo(2);

        HttpResponse<String> export = send(admin, "POST", "/v1/admin/subjects/export", subjectBody(subject));

        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat(export.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(export.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"verso-kvkk-export.json\"");
        assertThat(export.headers().firstValue("X-Subject-Export-Truncated")).hasValue("false");
        String json = export.body();
        assertThat((String) JsonPath.read(json, "$.subject")).isEqualTo(subject);
        assertThat((List<String>) JsonPath.read(json, "$.conversations[*].turns[*].question"))
                .containsExactly("topic-leave A sorusu?");
        assertThat((String) JsonPath.read(json, "$.conversations[0].turns[0].feedback.reason")).isEqualTo("OUTDATED");
        assertThat((List<String>) JsonPath.read(json, "$.documents.personal[*].fileName")).containsExactly("izin-a.pdf");
        assertThat((List<String>) JsonPath.read(json, "$.auditEventsAsActor[*].action")).contains("QUESTION_ASKED",
                "FEEDBACK_GIVEN");
        assertThat(json).doesNotContain(other).doesNotContain("B sorusu").doesNotContain("izin-b.pdf")
                .doesNotContain(adminSub).doesNotContain("\"traceId\"");
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ? and action = ? and "
                + "target_type = 'SUBJECT'", adminSub, "SUBJECT_EXPORTED")).isOne();
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ? and action = ?", adminSub,
                "SUBJECT_VIEWED")).isOne();
        assertNoContentLogged("A sorusu", "izin-a.pdf", subject);
    }

    /**
     * Goal 2, erasure: history, feedback, activity days and personal documents (with every derived row) go; the
     * collection document, the grant and the audit rows stay and are reported; another account is untouched; a repeat
     * removes nothing more.
     */
    @Test
    void delete_whenConfirmed_removesTheSubjectKeepsCollectionAndAuditAndIsIdempotent() throws Exception {
        UUID personal = ready(subject, "topic-leave Annual leave is twenty working days.", "izin-a.pdf");
        UUID othersDocument = ready(other, "topic-leave Annual leave is twenty working days.", "izin-b.pdf");
        // A collection the subject may read by name, and a document the subject uploaded into it (as verso-admin);
        // inserted after the worker ran, so it stays PENDING and never needs a file.
        UUID collection = jdbc.queryForObject("INSERT INTO document.collection (name, created_by) VALUES (?, ?) RETURNING id",
                UUID.class, "Alan " + UUID.randomUUID(), adminSub);
        jdbc.update("INSERT INTO document.collection_grant (collection_id, principal, created_by) VALUES (?, ?, ?)",
                collection, "user:" + subject, adminSub);
        DocumentRow shared = documents.insert(subject, collection, "ortak.pdf", DocumentFormat.PDF, 10, null, Instant.now());
        TestChatModel.INSTANCE.answer("Yirmi iş günü [1].");
        rate(subject, ask(subject, "topic-leave A sorusu?"), "{\"rating\":\"UP\"}");
        ask(subject, "topic-leave A ikinci sorusu?");
        ask(other, "topic-leave B sorusu?");
        long subjectAuditRows = AuditTables.count("select count(*) from audit.event where actor_sub = ?", subject);
        assertThat(subjectAuditRows).isGreaterThanOrEqualTo(3);

        HttpResponse<String> response = send(admin, "POST", "/v1/admin/subjects/delete", deletionBody(subject, subject));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        String body = response.body();
        assertThat((Integer) JsonPath.read(body, "$.historyDeleted.conversations")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(body, "$.historyDeleted.turns")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(body, "$.historyDeleted.feedback")).isOne();
        assertThat((Integer) JsonPath.read(body, "$.historyDeleted.activeDays")).isOne();
        assertThat((Integer) JsonPath.read(body, "$.personalDocumentsDeleted")).isOne();
        assertThat((Integer) JsonPath.read(body, "$.kept.documents.personalCount")).isZero();
        assertThat((Integer) JsonPath.read(body, "$.kept.documents.collectionCount")).isOne();
        assertThat((List<String>) JsonPath.read(body, "$.kept.documents.collection[*].id")).containsExactly(shared.id().toString());
        assertThat((List<String>) JsonPath.read(body, "$.kept.documents.grantedCollections[*]"))
                .containsExactly(collection.toString());
        assertThat(((Number) JsonPath.read(body, "$.kept.auditEventsAsActor")).longValue()).isEqualTo(subjectAuditRows);

        Map<String, Long> left = rowsNaming(personal);
        assertThat(left.keySet()).as("personal document and everything derived; only the documented references stay")
                .isSubsetOf(KEPT_REFERENCES).noneMatch(key -> key.startsWith("document."));
        assertThat(QaTables.count("select count(*) from qa.conversation where account_id = ?", subject)).isZero();
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ?", subject)).isZero();
        assertThat(QaTables.count("select count(*) from qa.active_account_day where account_id = ?", subject)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from document.document where id = ?", Long.class, shared.id()))
                .as("collection document kept").isOne();
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ?", subject))
                .as("audit rows are append-only").isEqualTo(subjectAuditRows);
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ? and action = 'SUBJECT_DELETED' "
                + "and target_type = 'SUBJECT' and target_id = ? and detail_code = 'COLLECTION_DOCUMENTS_KEPT'", adminSub,
                UUID.nameUUIDFromBytes(("verso:subject:" + subject).getBytes(StandardCharsets.UTF_8)))).isOne();
        // The other account is untouched.
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ?", other)).isOne();
        assertThat(rowsNaming(othersDocument)).containsKey("document.document_chunk.document_id");

        HttpResponse<String> repeat = send(admin, "POST", "/v1/admin/subjects/delete", deletionBody(subject, subject));
        assertThat(repeat.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(repeat.body(), "$.historyDeleted.conversations")).isZero();
        assertThat((Integer) JsonPath.read(repeat.body(), "$.personalDocumentsDeleted")).isZero();
        assertThat((Integer) JsonPath.read(repeat.body(), "$.kept.documents.collectionCount")).isOne();
        assertNoContentLogged("A sorusu", "izin-a.pdf", "ortak.pdf", subject);
    }

    /** Goal 3: two erasures of the same subject at once both succeed and remove every row exactly once. */
    @Test
    void delete_whenFiredTwiceAtOnce_bothSucceedAndCountEachRowOnce() throws Exception {
        for (int i = 0; i < 3; i++) ready(subject, "topic-leave Annual leave is twenty working days.", "izin-" + i + ".pdf");
        TestChatModel.INSTANCE.answer("Yirmi iş günü [1].");
        for (int i = 0; i < 4; i++) ask(subject, "topic-leave soru " + i + "?");
        String path = "/v1/admin/subjects/delete";
        String body = deletionBody(subject, subject);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<HttpResponse<String>> call = () -> send(admin, "POST", path, body);
            List<Future<HttpResponse<String>>> futures = pool.invokeAll(List.of(call, call));
            int conversations = 0;
            int personalDocuments = 0;
            for (Future<HttpResponse<String>> future : futures) {
                HttpResponse<String> response = future.get();
                assertThat(response.statusCode()).isEqualTo(200);
                conversations += (Integer) JsonPath.read(response.body(), "$.historyDeleted.conversations");
                personalDocuments += (Integer) JsonPath.read(response.body(), "$.personalDocumentsDeleted");
            }
            assertThat(conversations).isEqualTo(4);
            assertThat(personalDocuments).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from document.document where account_id = ?", Long.class, subject))
                .isZero();
        assertThat(QaTables.count("select count(*) from qa.conversation where account_id = ?", subject)).isZero();
    }

    /** Goal 5: the confirmation is required and must repeat the sub; the sub must look like a token subject. */
    @Test
    void delete_whenTheConfirmationIsMissingOrWrong_isRejectedAndDeletesNothing() throws Exception {
        TestChatModel.INSTANCE.answer("Yirmi iş günü [1].");
        ready(subject, "topic-leave Annual leave is twenty working days.", "izin.pdf");
        ask(subject, "topic-leave A sorusu?");
        String path = "/v1/admin/subjects/delete";
        String[] bodies = {"", "{}", "{\"sub\":\"" + subject + "\"}", deletionBody(subject, ""), deletionBody(subject, other),
                deletionBody(subject, subject.toUpperCase()), deletionBody(subject, "x".repeat(256)), "not json"};
        for (String body : bodies) {
            HttpResponse<String> response = send(admin, "POST", path, body);
            assertThat(response.statusCode()).as(String.valueOf(body)).isEqualTo(400);
            assertThat(response.body()).as(String.valueOf(body)).contains("\"ok\":false").doesNotContain(subject);
        }
        // A well-formed body with the wrong confirmation is the privacy module's own refusal, not a parse error.
        HttpResponse<String> mismatch = send(admin, "POST", path, deletionBody(subject, other));
        assertThat(mismatch.body()).contains("\"code\":13002").doesNotContain(subject).doesNotContain(other);
        assertThat(send(admin, "POST", path, deletionBody(subject, subject.toUpperCase())).body()).contains("\"code\":13002");
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ?", subject)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from document.document where account_id = ?", Long.class, subject))
                .isOne();
        for (String bad : new String[]{"a!b", "a b", "a*b", "a/b", "a%"}) {
            for (String endpoint : new String[]{"summary", "export"}) {
                HttpResponse<String> response = send(admin, "POST", "/v1/admin/subjects/" + endpoint, subjectBody(bad));
                assertThat(response.statusCode()).as(bad).isEqualTo(400);
                assertThat(response.body()).as(bad).contains("\"code\":13001").doesNotContain(bad);
            }
        }
        assertThat(send(admin, "POST", "/v1/admin/subjects/summary", subjectBody("x".repeat(256))).statusCode()).isEqualTo(400);
        assertThat(send(admin, "POST", "/v1/admin/subjects/delete", deletionBody("a!b", "a!b")).body())
                .contains("\"code\":13001");
    }

    /** ADR-0019: every endpoint is verso-admin only; the denial itself is audited (ADR-0026). */
    @Test
    void endpoints_whenCallerIsNotAnAdmin_areDenied() throws Exception {
        String user = TestIdp.bearer(subject);
        String operator = "Bearer " + TestIdp.IDP.token().subject("acct-operator").realmRoles("verso-operator", "verso-user")
                .build();
        String[][] calls = {{"POST", "/v1/admin/subjects/summary", subjectBody(other)},
                {"POST", "/v1/admin/subjects/export", subjectBody(other)},
                {"POST", "/v1/admin/subjects/delete", deletionBody(other, other)},
                {"POST", "/v1/admin/documents/" + UUID.randomUUID() + "/answer-redaction", "{\"confirm\":\"" + UUID.randomUUID() + "\"}"}};
        for (String[] call : calls) {
            for (String caller : new String[]{user, operator}) {
                HttpResponse<String> denied = send(caller, call[0], call[1], call[2]);
                assertThat(denied.statusCode()).as(call[0] + " " + call[1]).isEqualTo(403);
                assertThat(denied.body()).contains("\"code\":90101").doesNotContain(other);
            }
            assertThat(send(null, call[0], call[1], call[2]).statusCode()).as("anonymous").isEqualTo(401);
        }
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ? and action = 'ACCESS_DENIED' "
                + "and detail_code = 'ADMIN_SUBJECT'", subject)).isEqualTo(3);
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ? and action = 'ACCESS_DENIED' "
                + "and detail_code = 'ADMIN_ANSWER_REDACTION'", subject)).isOne();
    }

    /** Every row in any schema whose uuid (or uuid[]) column names the document, by "schema.table.column". */
    private static Map<String, Long> rowsNaming(UUID document) throws Exception {
        Map<String, Long> found = new LinkedHashMap<>();
        try (Connection admin = AuditTables.admin()) {
            List<String[]> columns = new ArrayList<>();
            try (PreparedStatement st = admin.prepareStatement("""
                    select table_schema, table_name, column_name, data_type from information_schema.columns
                    where table_schema in ('document', 'qa', 'audit')
                      and (data_type = 'uuid' or (data_type = 'ARRAY' and udt_name = '_uuid'))
                      and table_name in (select table_name from information_schema.tables
                                         where table_schema = columns.table_schema and table_type = 'BASE TABLE')""");
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) columns.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)});
            }
            assertThat(columns).as("the scan sees the tables").hasSizeGreaterThan(10);
            for (String[] c : columns) {
                String predicate = c[3].equals("ARRAY") ? "? = any(\"" + c[2] + "\")" : "\"" + c[2] + "\" = ?";
                try (PreparedStatement st = admin.prepareStatement("select count(*) from \"" + c[0] + "\".\"" + c[1]
                        + "\" where " + predicate)) {
                    st.setObject(1, document);
                    try (ResultSet rs = st.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) > 0) found.put(c[0] + "." + c[1] + "." + c[2], rs.getLong(1));
                    }
                }
            }
        }
        return found;
    }

    private static String subjectBody(String sub) {
        return "{\"sub\":\"" + sub + "\"}";
    }

    private static String deletionBody(String sub, String confirm) {
        return "{\"sub\":\"" + sub + "\",\"confirm\":\"" + confirm + "\"}";
    }

    private void assertNoContentLogged(String... secrets) {
        // Rendered message, raw arguments, MDC and any exception text: none may carry content or the subject's sub.
        List<String> lines = appender.list.stream().map(e -> e.getFormattedMessage() + " " + java.util.Arrays.toString(
                e.getArgumentArray()) + " " + e.getMDCPropertyMap() + " " + (e.getThrowableProxy() == null ? ""
                : e.getThrowableProxy().getMessage())).toList();
        for (String secret : secrets) assertThat(lines).as(secret).noneMatch(line -> line.contains(secret));
        assertThat(lines).anyMatch(line -> line.contains("outcome="));
    }

    private UUID ready(String account, String text, String fileName) {
        DocumentRow row = documents.insert(account, fileName, DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(text));
        worker.runOnce();
        assertThat(documents.find(account, row.id()).orElseThrow().status().name()).isEqualTo("READY");
        return row.id();
    }

    private HttpResponse<String> ask(String account, String question) throws Exception {
        HttpResponse<String> response = send(TestIdp.bearer(account), "POST", "/v1/questions",
                "{\"question\":\"" + question + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.turnId")).isNotBlank();
        return response;
    }

    private void rate(String account, HttpResponse<String> answer, String body) throws Exception {
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");
        assertThat(send(TestIdp.bearer(account), "POST", "/v1/conversations/" + conversation + "/turns/" + turn
                + "/feedback", body).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(String authorization, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60));
        if (authorization != null) request.header("Authorization", authorization);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
