package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.api.dto.DocumentChunkResponse;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.AuditTables;
import com.verso.support.Multipart;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * GET /v1/admin/documents/{id}/chunks (ADR-0036) on a real port and the real database: verso-admin only (403 and an
 * ACCESS_DENIED audit row for anyone else, 401 without a token); the admin reads only documents its own principals
 * read (another account's personal document, a missing id and an ungranted collection are the same 404, ADR-0028); a
 * revoked grant is gone at the next request; chunk order, paging, the size caps and the text cap; no-store; no audit
 * row for a read; no chunk text in any log (llm-rules 2.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class AdminDocumentChunkApiTest {

    private static final String MARKER = "ChunkInspectorPrivacyMarker";

    @Value("${local.server.port}")
    int port;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String userSub = "acct-chunk-user-" + suffix;
    private final String user = TestIdp.bearer(userSub);
    private final String adminSub = "acct-chunk-admin-" + suffix;
    private final String admin = "Bearer " + TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection");
        AuditTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
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
    void chunks_whenTheAdminReadsTheDocument_returnsItsChunksInOrderWithoutAnAuditRowOrALogLine() throws Exception {
        UUID id = ready(adminSub, "rapor.pdf", DocumentFormat.PDF,
                TestPdfs.pages("First page " + MARKER + ".", "Second page text."));

        HttpResponse<String> response = get(admin, id, "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat(response.body()).contains("\"unit\":\"PAGE\"", "\"truncated\":false", MARKER)
                .doesNotContain("embedding");
        List<Integer> indexes = JsonPath.read(response.body(), "$.data[*].index");
        List<Integer> numbers = JsonPath.read(response.body(), "$.data[*].number");
        assertThat(indexes).isSortedAccordingTo(Integer::compare).hasSizeGreaterThanOrEqualTo(2);
        assertThat(numbers).contains(1, 2);
        assertThat((Integer) JsonPath.read(response.body(), "$.page.totalElements")).isEqualTo(indexes.size());
        String first = JsonPath.read(response.body(), "$.data[0].text");
        assertThat((Integer) JsonPath.read(response.body(), "$.data[0].charCount"))
                .isEqualTo(first.codePointCount(0, first.length()));
        // A read: no audit row (ADR-0026), and no chunk text in any log line.
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ?", adminSub)).isZero();
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage() + event.getMDCPropertyMap()).doesNotContain(MARKER, "Second page");
        }
    }

    /** ADR-0016: sections of a document without pages carry the SECTION unit. */
    @Test
    void chunks_whenTheDocumentHasSections_carryTheSectionUnit() throws Exception {
        UUID id = ready(adminSub, "notlar.md", DocumentFormat.MD,
                "# Bir\nbirinci\n# İki\nikinci".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = get(admin, id, "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"unit\":\"SECTION\"", "ikinci").doesNotContain("\"unit\":\"PAGE\"");
    }

    @Test
    void chunks_whenTheCallerIsNotAnAdmin_is403AndAudited_andWithoutATokenIs401() throws Exception {
        UUID id = ready(userSub, "rapor.pdf", DocumentFormat.PDF, TestPdfs.pages("Own text " + MARKER + "."));

        HttpResponse<String> denied = get(user, id, "");

        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("\"code\":90101").doesNotContain(MARKER);
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ? and action = 'ACCESS_DENIED'"
                + " and detail_code = 'ADMIN_DOCUMENT_CHUNK'", userSub)).isEqualTo(1);
        assertThat(http.send(HttpRequest.newBuilder(uri(id, "")).GET().build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(401);
    }

    /** IDOR: the admin role is no reading right; another account's document looks like a missing one. */
    @Test
    void chunks_whenTheAdminCannotReadTheDocument_isTheSame404AsAMissingOne() throws Exception {
        UUID id = ready(userSub, "rapor.pdf", DocumentFormat.PDF, TestPdfs.pages("Private " + MARKER + "."));

        HttpResponse<String> foreign = get(admin, id, "");
        HttpResponse<String> missing = get(admin, UUID.randomUUID(), "");

        assertNotFound(foreign);
        assertNotFound(missing);
        assertThat(foreign.body()).doesNotContain(MARKER);
        assertThat(strip(foreign.body())).isEqualTo(strip(missing.body()));
    }

    /** ADR-0028: managing a collection is not reading it; a grant to the admin's sub is, until it is revoked. */
    @Test
    void chunks_whenTheDocumentIsInACollection_followsTheAdminsOwnGrant() throws Exception {
        UUID hukuk = UUID.fromString(JsonPath.read(send(admin, "POST", "/v1/collections", "{\"name\":\"Hukuk " + suffix
                + "\"}").body(), "$.id"));
        UUID id = uploadInto(hukuk, "Shared contract text.");

        assertNotFound(get(admin, id, ""));

        String grant = JsonPath.read(send(admin, "POST", "/v1/collections/" + hukuk + "/grants",
                "{\"principalType\":\"USER\",\"principal\":\"" + adminSub + "\"}").body(), "$.id");
        HttpResponse<String> granted = get(admin, id, "");
        assertThat(granted.statusCode()).isEqualTo(200);
        assertThat(granted.body()).contains("Shared contract text.");

        assertThat(send(admin, "DELETE", "/v1/collections/" + hukuk + "/grants/" + grant, null).statusCode())
                .isEqualTo(204);
        assertNotFound(get(admin, id, ""));
    }

    @Test
    void chunks_whenPaged_returnsTheAskedSliceAndRejectsOutOfRangeParameters() throws Exception {
        UUID id = ready(adminSub, "rapor.pdf", DocumentFormat.PDF, TestPdfs.pages("Bir.", "İki.", "Üç."));
        int total = JsonPath.read(get(admin, id, "").body(), "$.page.totalElements");

        HttpResponse<String> second = get(admin, id, "?page=1&size=1");
        HttpResponse<String> past = get(admin, id, "?page=" + total + "&size=1");

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(second.body(), "$.data[0].index")).isEqualTo(
                (Integer) JsonPath.read(get(admin, id, "").body(), "$.data[1].index"));
        assertThat((Integer) JsonPath.read(second.body(), "$.page.totalPages")).isEqualTo(total);
        assertThat(past.statusCode()).isEqualTo(200);
        assertThat((List<?>) JsonPath.read(past.body(), "$.data")).isEmpty();
        assertThat(get(admin, id, "?size=0").statusCode()).isEqualTo(400);
        assertThat(get(admin, id, "?size=101").statusCode()).isEqualTo(400);
        assertThat(get(admin, id, "?page=-1").statusCode()).isEqualTo(400);
        assertThat(get(admin, id, "?page=100001").statusCode()).isEqualTo(400);
    }

    /** A chunk above the cap is cut at the cap (code points) and says so; charCount stays the whole length. */
    @Test
    void chunks_whenAChunkIsLongerThanTheCap_isCutAndMarked() throws Exception {
        UUID id = ready(adminSub, "uzun.pdf", DocumentFormat.PDF, TestPdfs.pages("Short."));
        String longText = "😀" + "a".repeat(DocumentChunkResponse.MAX_TEXT_CHARS + 50);
        // Chunks are written and deleted, never updated (no UPDATE grant): one long copy replaces them.
        jdbc.update("INSERT INTO document.document_chunk (document_id, account_id, page_number, chunk_index, content, "
                + "embedding, embedding_model) SELECT document_id, account_id, page_number, 100000, ?, embedding, "
                + "embedding_model FROM document.document_chunk WHERE document_id = ? ORDER BY chunk_index LIMIT 1",
                longText, id);
        jdbc.update("DELETE FROM document.document_chunk WHERE document_id = ? AND chunk_index < 100000", id);

        HttpResponse<String> response = get(admin, id, "");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"truncated\":true");
        String text = JsonPath.read(response.body(), "$.data[0].text");
        assertThat(text.codePointCount(0, text.length())).isEqualTo(DocumentChunkResponse.MAX_TEXT_CHARS);
        assertThat(text).startsWith("😀");
        assertThat((Integer) JsonPath.read(response.body(), "$.data[0].charCount"))
                .isEqualTo(DocumentChunkResponse.MAX_TEXT_CHARS + 51);
    }

    private UUID ready(String account, String fileName, DocumentFormat format, byte[] content) {
        DocumentRow row = documents.insert(account, fileName, format, content.length, null, Instant.now());
        documents.insertFile(row.id(), content);
        worker.runOnce();
        assertThat(documents.find(account, row.id()).orElseThrow().status().name()).isEqualTo("READY");
        return row.id();
    }

    private UUID uploadInto(UUID collection, String text) throws Exception {
        Multipart body = Multipart.pdf("sozlesme.pdf", TestPdfs.pages(text));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/v1/documents?collectionId=" + collection)).header("Authorization", admin)
                .header("Content-Type", body.contentType()).timeout(Duration.ofSeconds(30)).POST(body.body()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        for (int i = 0; i < 5; i++) worker.runOnce();
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private URI uri(UUID id, String query) {
        return URI.create("http://localhost:" + port + "/v1/admin/documents/" + id + "/chunks" + query);
    }

    private HttpResponse<String> get(String authorization, UUID id, String query) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(id, query)).header("Authorization", authorization)
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String authorization, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", authorization).timeout(Duration.ofSeconds(30));
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The envelope without the fields that differ per request (path, time, trace id). */
    private static String strip(String body) {
        return body.replaceAll("\"(path|timestamp|traceId)\":(\"[^\"]*\"|\\d+)", "");
    }

    private static void assertNotFound(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.request().uri() + " " + response.body()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":10001");
    }
}
