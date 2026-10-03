package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.testing.TestDocx;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.platform.observability.tracing.TraceIds;
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
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * /v1/documents end to end on a real port and the real database (ADR-0011): upload validation and codes, ownership
 * (another account's document does not exist for the caller), idempotent retries, deletion with everything derived,
 * and the API version header. The worker is driven by hand; its own rules are in IngestionWorkerTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@VersoTestEnvironment
class DocumentApiTest {

    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestionWorker worker;

    @Autowired
    org.springframework.transaction.support.TransactionTemplate transaction;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String owner = "acct-doc-" + UUID.randomUUID();
    private final String other = "acct-other-" + UUID.randomUUID();

    @BeforeEach
    void cleanJobs() {
        // The worker claims any due document; earlier tests must not leave work behind for this one.
        jdbc.update("DELETE FROM document.document");
        TestEmbeddingModel.INSTANCE.reset();
    }

    @Test
    void upload_whenPdf_returns201WithLocationAndAPendingPrivateDocument() throws Exception {
        HttpResponse<String> response = upload(owner, "Kira Sözleşmesi.pdf", TestPdfs.pages("Lease text."), null);

        assertThat(response.statusCode()).isEqualTo(201);
        String id = id(response);
        assertThat(response.headers().firstValue("Location")).hasValue("/v1/documents/" + id);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store").contains("private");
        assertThat(response.body()).contains("\"status\":\"PENDING\"").contains("\"fileName\":\"Kira Sözleşmesi.pdf\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document_file WHERE document_id = ?::uuid",
                Integer.class, id)).as("the PDF waits for the worker").isOne();
    }

    /** ADR-0016: DOCX, TXT and MD are accepted and keep their format; the media type the client sends is ignored. */
    @Test
    void upload_whenDocxTxtOrMd_returns201WithTheFormat() throws Exception {
        Map<String, byte[]> files = Map.of(
                "İzin.docx", TestDocx.docx(TestDocx.p("Yıllık izin on dört gündür.")),
                "notlar.txt", "Yıllık izin on dört gündür.".getBytes(StandardCharsets.UTF_8),
                "README.md", "# İzin\nOn dört gün.".getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            HttpResponse<String> response = upload(owner, file.getKey(), file.getValue(), null);
            assertThat(response.statusCode()).as(file.getKey()).isEqualTo(201);
            String format = file.getKey().substring(file.getKey().lastIndexOf('.') + 1).toUpperCase(java.util.Locale.ROOT);
            assertThat(response.body()).contains("\"format\":\"" + format + "\"");
            assertThat(jdbc.queryForObject("SELECT format FROM document.document WHERE id = ?::uuid", String.class,
                    id(response))).isEqualTo(format);
        }
    }

    /** ADR-0016: other names, a ZIP that is not named .docx, and binary bytes named .txt stay 415 10010. */
    @Test
    void upload_whenTheTypeIsNotSupported_isRejectedWith10010() throws Exception {
        Map<String, byte[]> files = Map.of(
                "setup.exe", "MZ program".getBytes(StandardCharsets.UTF_8),
                "arsiv.zip", TestDocx.docx(TestDocx.p("x")),
                "ikili.txt", new byte[] {'M', 'Z', 0, 0, 1},
                "eski.docx", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0});
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            HttpResponse<String> response = upload(owner, file.getKey(), file.getValue(), null);
            assertThat(response.statusCode()).as(file.getKey()).isEqualTo(415);
            assertThat(response.body()).contains("\"code\":10010");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document", Integer.class)).isZero();
    }

    @Test
    void upload_whenFileIsNotAPdfOrEmpty_isRejectedWithDocumentCodes() throws Exception {
        HttpResponse<String> notPdf = upload(owner, "x.pdf", "just text".getBytes(), null);
        assertThat(notPdf.statusCode()).isEqualTo(415);
        assertThat(notPdf.body()).contains("\"code\":10010").contains("\"service\":\"document\"");

        HttpResponse<String> empty = upload(owner, "x.pdf", new byte[0], null);
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(empty.body()).contains("\"code\":10011");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document", Integer.class)).isZero();
    }

    @Test
    void upload_whenFileIsLargerThanTheLimit_isRejectedWith413BeforeTheController() throws Exception {
        // Above max-file-size (20 MB), below max-request-size (21 MB): Tomcat reads the rest and answers cleanly.
        byte[] large = new byte[20 * 1024 * 1024 + 512 * 1024];
        System.arraycopy("%PDF-1.7".getBytes(), 0, large, 0, 8);
        HttpResponse<String> response = upload(owner, "big.pdf", large, null);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"code\":90014");
    }

    @Test
    void upload_whenTheFilePartIsMissing_isRejectedWith400() throws Exception {
        Multipart wrongPart = Multipart.file("document", "a.pdf", "application/pdf", TestPdfs.pages("x"));
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/v1/documents"))
                .header("Authorization", TestIdp.bearer(owner)).header("Content-Type", wrongPart.contentType())
                .POST(wrongPart.body()));
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"ok\":false");
    }

    /** Reference 6.4: a retried upload with the same key returns the first document and stores nothing new. */
    @Test
    void upload_whenRepeatedWithTheSameIdempotencyKey_returnsTheFirstDocument() throws Exception {
        UUID key = UUID.randomUUID();
        String first = id(upload(owner, "a.pdf", TestPdfs.pages("one"), key));
        HttpResponse<String> again = upload(owner, "b.pdf", TestPdfs.pages("two"), key);

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(id(again)).isEqualTo(first);
        assertThat(again.body()).contains("\"fileName\":\"a.pdf\"");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document", Integer.class)).isOne();
        // The key is per account: the same key from another account is a new document.
        assertThat(id(upload(other, "c.pdf", TestPdfs.pages("three"), key))).isNotEqualTo(first);
    }

    /** IDOR (reference 6.5): another account's document cannot be read, listed or deleted; it answers 404. */
    @Test
    void documents_whenAccessedByAnotherAccount_doNotExistForIt() throws Exception {
        String id = id(upload(owner, "private.pdf", TestPdfs.pages("secret plan"), null));

        HttpResponse<String> read = send(get("/v1/documents/" + id, other));
        assertThat(read.statusCode()).isEqualTo(404);
        assertThat(read.body()).contains("\"code\":10001").doesNotContain("private.pdf");
        assertThat(send(get("/v1/documents", other)).body()).contains("\"totalElements\":0").doesNotContain(id);
        assertThat(send(delete("/v1/documents/" + id, other)).statusCode()).isEqualTo(404);

        assertThat(send(get("/v1/documents/" + id, owner)).statusCode()).isEqualTo(200);
        assertThat(send(get("/v1/documents", owner)).body()).contains(id).contains("\"totalElements\":1");
    }

    /** Test review T3: the same key sent in parallel creates one document; every request gets it (reference 6.4). */
    @Test
    void upload_whenTheSameKeyIsSentInParallel_createsOneDocument() throws Exception {
        UUID key = UUID.randomUUID();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Callable<HttpResponse<String>>> uploads = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            uploads.add(() -> {
                start.await();
                return upload(owner, "same.pdf", TestPdfs.pages("same"), key);
            });
        }
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = uploads.stream().map(pool::submit).toList();
            start.countDown();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (var future : futures) {
                HttpResponse<String> response = future.get();
                assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
                ids.add(id(response));
            }
            assertThat(ids).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document_file", Integer.class)).isOne();
    }

    /** Test review T7: the stored label is the cleaned name, not what the client sent. */
    @Test
    void upload_whenTheFileNameHasAPathAndInvisibleCharacters_storesOnlyTheCleanLabel() throws Exception {
        HttpResponse<String> response = upload(owner, "../x/rap\u202Eor.pdf", TestPdfs.pages("x"), null);
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"fileName\":\"rapor.pdf\"");
        assertThat(jdbc.queryForObject("SELECT file_name FROM document.document WHERE id = ?::uuid", String.class,
                id(response))).isEqualTo("rapor.pdf");
    }

    /** Test review T8: "%PDF-" is accepted anywhere within the first 1024 bytes and nowhere later. */
    @Test
    void upload_whenTheHeaderIsWithinOrBeyondTheFirst1024Bytes_isAcceptedOrRejected() throws Exception {
        assertThat(upload(owner, "in.pdf", withHeaderAt(1019), null).statusCode()).isEqualTo(201);
        HttpResponse<String> beyond = upload(owner, "out.pdf", withHeaderAt(1020), null);
        assertThat(beyond.statusCode()).isEqualTo(415);
        assertThat(beyond.body()).contains("\"code\":10010");
    }

    /** Test review T9: reading one document returns its file name, so it is private and not stored by caches. */
    @Test
    void get_whenTheOwnerReads_answersPrivateNoStore() throws Exception {
        String id = id(upload(owner, "private.pdf", TestPdfs.pages("x"), null));
        HttpResponse<String> response = send(get("/v1/documents/" + id, owner));
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store").contains("private");
    }

    private static byte[] withHeaderAt(int offset) {
        byte[] content = new byte[offset + 64];
        java.util.Arrays.fill(content, (byte) ' ');
        System.arraycopy("%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, content, offset, 8);
        return content;
    }

    /** KVKK erasure (llm-rules 4.2): one DELETE removes the document with its pages, chunks and vectors. */
    @Test
    void delete_whenDocumentIsReady_removesPagesChunksAndVectors() throws Exception {
        String id = id(upload(owner, "a.pdf", TestPdfs.pages("first page text", "second page text"), null));
        assertThat(worker.runOnce()).isOne();
        assertThat(send(get("/v1/documents/" + id, owner)).body()).contains("\"status\":\"READY\"");

        assertThat(send(delete("/v1/documents/" + id, owner)).statusCode()).isEqualTo(204);

        for (String table : new String[]{"document", "document_file", "document_page", "document_chunk"}) {
            String column = table.equals("document") ? "id" : "document_id";
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document." + table + " WHERE " + column + " = ?::uuid",
                    Integer.class, id)).as(table).isZero();
        }
        assertThat(send(get("/v1/documents/" + id, owner)).statusCode()).isEqualTo(404);
    }

    /**
     * Review D2: the worker's transaction storing a large document holds the row for seconds; a KVKK deletion waits
     * for it instead of failing at the role's 3 s lock timeout.
     */
    @Test
    void delete_whenTheRowIsLockedForSeconds_waitsAndSucceeds() throws Exception {
        String id = id(upload(owner, "locked.pdf", TestPdfs.pages("text"), null));
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> transaction.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM document.document WHERE id = ?::uuid FOR UPDATE", id);
            locked.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        locked.await();
        assertThat(send(delete("/v1/documents/" + id, owner)).statusCode()).isEqualTo(204);
        holder.join();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document WHERE id = ?::uuid", Integer.class, id)).isZero();
    }

    @Test
    void list_whenPagedOrOutOfRange_returnsThePageShapeOrRejects() throws Exception {
        for (int i = 0; i < 3; i++) upload(owner, "d" + i + ".pdf", TestPdfs.pages("text " + i), null);
        HttpResponse<String> page = send(get("/v1/documents?page=1&size=2", owner));
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("\"page\":{\"number\":1,\"size\":2,\"totalElements\":3,\"totalPages\":2}");
        assertThat(page.headers().firstValue("Cache-Control").orElse("")).contains("no-store");

        assertThat(send(get("/v1/documents?size=101", owner)).statusCode()).isEqualTo(400);
        assertThat(send(get("/v1/documents?page=-1", owner)).statusCode()).isEqualTo(400);
    }

    /** ADR-0004: API-Version is optional and 1.0 by default; an unsupported version is 400 API_VERSION_INVALID. */
    @Test
    void apiVersion_whenAbsentOrSupportedOrUnknown_isAcceptedOrRejected() throws Exception {
        assertThat(send(get("/v1/documents", owner)).statusCode()).isEqualTo(200);
        assertThat(send(get("/v1/documents", owner).header("API-Version", "1.0")).statusCode()).isEqualTo(200);
        HttpResponse<String> unknown = send(get("/v1/documents", owner).header("API-Version", "2.0"));
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.body()).contains("\"code\":90020");
    }

    /** Review P6: malformed ids and keys are binding errors (400 90002) and never reach the service. */
    @Test
    void request_whenIdOrIdempotencyKeyIsMalformed_isRejectedWith400() throws Exception {
        HttpResponse<String> badId = send(get("/v1/documents/not-a-uuid", owner));
        assertThat(badId.statusCode()).isEqualTo(400);
        assertThat(badId.body()).contains("\"code\":90002");
        Multipart body = Multipart.pdf("a.pdf", TestPdfs.pages("x"));
        HttpResponse<String> badKey = send(HttpRequest.newBuilder(uri("/v1/documents"))
                .header("Authorization", TestIdp.bearer(owner)).header("Content-Type", body.contentType())
                .header("X-Idempotency-Key", "not-a-uuid").POST(body.body()));
        assertThat(badKey.statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document", Integer.class)).isZero();
    }

    /** llm-rules 2.1 on the upload path: neither the file name nor the content reaches any log of the request. */
    @Test
    void upload_whenAcceptedOrRejected_logsNoFileNameOrContent() throws Exception {
        String marker = "MarkerPayrollOfJohnRoe";
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            assertThat(upload(owner, marker + ".pdf", TestPdfs.pages(marker), null).statusCode()).isEqualTo(201);
            assertThat(upload(owner, marker + ".pdf", (marker + " not a pdf").getBytes(), null).statusCode()).isEqualTo(415);
            assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("outcome=accepted"))
                    .anyMatch(e -> e.getFormattedMessage().contains("code=DOCUMENT_TYPE_UNSUPPORTED"));
            for (var event : appender.list) {
                StringBuilder text = new StringBuilder(event.getFormattedMessage()).append(event.getMDCPropertyMap());
                if (event.getArgumentArray() != null) {
                    for (Object argument : event.getArgumentArray()) text.append(' ').append(argument);
                }
                for (var t = event.getThrowableProxy(); t != null; t = t.getCause()) text.append(' ').append(t.getMessage());
                assertThat(text.toString()).as(event.getLoggerName()).doesNotContain(marker).doesNotContain(owner);
            }
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void documents_whenCalledWithoutToken_answer401() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/v1/documents")).GET());
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues(TraceIds.HEADER)).hasSize(1);
    }

    private HttpResponse<String> upload(String account, String fileName, byte[] content, UUID key) throws Exception {
        Multipart body = Multipart.pdf(fileName, content);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/v1/documents"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", body.contentType())
                .POST(body.body());
        if (key != null) request.header("X-Idempotency-Key", key.toString());
        return send(request);
    }

    private HttpRequest.Builder get(String path, String account) {
        return HttpRequest.newBuilder(uri(path)).header("Authorization", TestIdp.bearer(account)).GET();
    }

    private HttpRequest.Builder delete(String path, String account) {
        return HttpRequest.newBuilder(uri(path)).header("Authorization", TestIdp.bearer(account)).DELETE();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    static String id(HttpResponse<String> response) {
        Matcher matcher = ID.matcher(response.body());
        assertThat(matcher.find()).as(response.body()).isTrue();
        return matcher.group(1);
    }
}
