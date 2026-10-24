package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.api.dto.DocumentPageResponse;
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
 * GET /v1/documents/{id}/pages/{number} (ADR-0035) on a real port and the real database: the owner reads every page and
 * only those; a section number for DOCX/TXT/MD; another account, a missing id and a collection without a grant are the
 * same 404 (IDOR, ADR-0028), for verso-admin too; a revoked grant is gone at the next request; the size cap; no audit
 * event for a read (ADR-0026); no page text in any log (llm-rules 2.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class DocumentPageApiTest {

    private static final String MARKER = "PageViewPrivacyMarker";

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
    private final String ownerSub = "acct-page-owner-" + suffix;
    private final String owner = TestIdp.bearer(ownerSub);
    private final String stranger = TestIdp.bearer("acct-page-stranger-" + suffix);
    private final String adminSub = "acct-page-admin-" + suffix;
    private final String admin = "Bearer " + TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    private final String alice = "Bearer " + TestIdp.IDP.token().subject("acct-page-alice-" + suffix)
            .claim("groups", List.of("/hukuk")).build();
    private final String bob = "Bearer " + TestIdp.IDP.token().subject("acct-page-bob-" + suffix)
            .claim("groups", List.of("/ik")).build();
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
    void page_whenTheOwnerAsks_returnsEachStoredPageAndNoOther() throws Exception {
        UUID id = ready(ownerSub, "rapor.pdf", DocumentFormat.PDF,
                TestPdfs.pages("First page " + MARKER + ".", "Second page text."));

        HttpResponse<String> first = get(owner, id, 1);
        HttpResponse<String> second = get(owner, id, 2);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat(first.headers().firstValue("X-Rag-Mode")).hasValue("local");
        assertThat(first.body()).contains("\"documentId\":\"" + id + "\"", "\"number\":1", "\"unit\":\"PAGE\"",
                "\"pageCount\":2", "\"truncated\":false", MARKER);
        assertThat((String) JsonPath.read(second.body(), "$.text")).contains("Second page text.");
        assertNotFound(get(owner, id, 3), 10005);
        assertThat(get(owner, id, 0).statusCode()).isEqualTo(400);
        assertThat(get(owner, id, 100_001).statusCode()).isEqualTo(400);
        assertThat(http.send(HttpRequest.newBuilder(uri(id, 1)).GET().build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(401);
        // A read: no audit row (ADR-0026), and no page text in any log line.
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ?", ownerSub)).isZero();
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage() + event.getMDCPropertyMap()).doesNotContain(MARKER, "Second page");
        }
    }

    /** IDOR: another account's document, a missing id and an unreadable page number all look the same to a stranger. */
    @Test
    void page_whenTheCallerCannotReadTheDocument_isTheSame404AsAMissingOne() throws Exception {
        UUID id = ready(ownerSub, "rapor.pdf", DocumentFormat.PDF, TestPdfs.pages("Private text."));

        HttpResponse<String> foreign = get(stranger, id, 1);
        HttpResponse<String> foreignMissingPage = get(stranger, id, 9);
        HttpResponse<String> missing = get(owner, UUID.randomUUID(), 1);

        assertNotFound(foreign, 10001);
        assertNotFound(foreignMissingPage, 10001);
        assertNotFound(missing, 10001);
        assertThat(foreign.body()).doesNotContain("Private");
        assertThat(strip(foreign.body())).isEqualTo(strip(missing.body()));
        // verso-admin reads no personal document either.
        assertNotFound(get(admin, id, 1), 10001);
    }

    /** ADR-0016: sections of a document without pages are numbered the same way and navigated within their count. */
    @Test
    void page_whenTheDocumentHasSections_returnsTheSectionUnit() throws Exception {
        UUID id = ready(ownerSub, "notlar.md", DocumentFormat.MD,
                "# Bir\nbirinci\n# İki\nikinci\n# Üç\nüçüncü".getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> second = get(owner, id, 2);

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).contains("\"unit\":\"SECTION\"", "\"pageCount\":3", "ikinci");
        assertNotFound(get(owner, id, 4), 10005);
    }

    /** ADR-0028: the grant decides, for verso-admin as for anyone; a revoked grant is gone at the next request. */
    @Test
    void page_whenTheDocumentIsInACollection_followsTheGrants() throws Exception {
        UUID hukuk = UUID.fromString(JsonPath.read(send(admin, "POST", "/v1/collections", "{\"name\":\"Hukuk " + suffix
                + "\"}").body(), "$.id"));
        String grant = JsonPath.read(send(admin, "POST", "/v1/collections/" + hukuk + "/grants",
                "{\"principalType\":\"GROUP\",\"principal\":\"/hukuk\"}").body(), "$.id");
        UUID id = uploadInto(hukuk, "Shared contract text.");

        assertThat(get(alice, id, 1).statusCode()).isEqualTo(200);
        assertThat(get(alice, id, 1).body()).contains("Shared contract text.");
        assertNotFound(get(bob, id, 1), 10001);
        assertNotFound(get(admin, id, 1), 10001);

        assertThat(send(admin, "DELETE", "/v1/collections/" + hukuk + "/grants/" + grant, null).statusCode())
                .isEqualTo(204);
        assertNotFound(get(alice, id, 1), 10001);
    }

    /** A page above the cap is cut at the cap (code points) and says so; nothing longer leaves the database. */
    @Test
    void page_whenThePageIsLongerThanTheCap_isCutAndMarked() throws Exception {
        UUID id = ready(ownerSub, "uzun.pdf", DocumentFormat.PDF, TestPdfs.pages("Short."));
        String longText = "😀" + "a".repeat(DocumentPageResponse.MAX_TEXT_CHARS + 50);
        jdbc.update("DELETE FROM document.document_page WHERE document_id = ? AND page_number = 1", id);
        jdbc.update("INSERT INTO document.document_page (document_id, page_number, content) VALUES (?, 1, ?)", id,
                longText);

        HttpResponse<String> response = get(owner, id, 1);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"truncated\":true");
        String text = JsonPath.read(response.body(), "$.text");
        assertThat(text.codePointCount(0, text.length())).isEqualTo(DocumentPageResponse.MAX_TEXT_CHARS);
        assertThat(text).startsWith("😀");
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

    private URI uri(UUID id, int number) {
        return URI.create("http://localhost:" + port + "/v1/documents/" + id + "/pages/" + number);
    }

    private HttpResponse<String> get(String authorization, UUID id, int number) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(id, number)).header("Authorization", authorization)
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

    private static void assertNotFound(HttpResponse<String> response, int code) {
        assertThat(response.statusCode()).as(response.request().uri() + " " + response.body()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":" + code);
    }
}
