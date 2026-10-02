package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.Duration;
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
