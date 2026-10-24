package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.VersoTestEnvironment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * ADR-0032 end to end in the application: the ingestion worker with the docling sidecar switched on, against a fake
 * docling-serve. The sidecar's pages are stored and a Markdown table becomes one chunk; a failing sidecar leaves the
 * JDK text in place (READY, not FAILED) and logs a fixed reason only; a PDF without a text layer waits for the sidecar
 * (retry) instead of failing. No log line carries the document's text or name (llm-rules 2.1).
 */
@SpringBootTest
@VersoTestEnvironment
class DoclingIngestionTest {

    private static final String MARKER = "MarkerQuarterlySalaryOfJaneDoe";
    private static final HttpServer SIDECAR;
    private static volatile int status = 200;

    static {
        try {
            SIDECAR = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SIDECAR.createContext("/", DoclingIngestionTest::handle);
        SIDECAR.start();
    }

    @DynamicPropertySource
    static void sidecar(DynamicPropertyRegistry registry) {
        registry.add("verso.document.docling.enabled", () -> "true");
        registry.add("verso.document.docling.base-url", () -> "http://127.0.0.1:" + SIDECAR.getAddress().getPort());
        registry.add("verso.document.docling.poll-interval", () -> "20ms");
        registry.add("verso.document.docling.document-timeout", () -> "5s");
        // The breaker has its own unit test (DoclingParserTest); here every document asks the sidecar.
        registry.add("verso.document.docling.circuit-failures", () -> "1000");
    }

    @AfterAll
    static void stopSidecar() {
        SIDECAR.stop(0);
    }

    private static final String DOCUMENT = """
            {"schema_name": "DoclingDocument", "version": "1.10.0",
             "body": {"self_ref": "#/body", "children": [{"$ref": "#/texts/0"}, {"$ref": "#/tables/0"}, {"$ref": "#/texts/1"}]},
             "texts": [
               {"self_ref": "#/texts/0", "label": "section_header", "prov": [{"page_no": 1}], "text": "Hız sınırları %1$s"},
               {"self_ref": "#/texts/1", "label": "text", "prov": [{"page_no": 2}], "text": "İkinci sayfa %1$s."}],
             "tables": [{"self_ref": "#/tables/0", "prov": [{"page_no": 1}], "data": {"grid": [
               [{"text": "Alan", "column_header": true}, {"text": "Azami hız", "column_header": true}],
               [{"text": "Raf koridorları", "column_header": false}, {"text": "6 km/sa", "column_header": false}]]}}],
             "groups": [], "pictures": [], "pages": {"1": {"page_no": 1}, "2": {"page_no": 2}}}""".formatted(MARKER);

    private static void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        String body;
        int code = status;
        if (code != 200) {
            body = "{\"detail\": \"" + MARKER + " could not be parsed\"}";
        } else if (path.equals("/v1/convert/file/async")) {
            body = "{\"task_id\": \"0b9d7c1e-1111-4222-8333-444455556666\", \"task_status\": \"pending\"}";
        } else if (path.startsWith("/v1/status/poll/")) {
            body = "{\"task_status\": \"success\"}";
        } else {
            body = "{\"status\": \"success\", \"document\": {\"json_content\": " + DOCUMENT + "}}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Autowired
    IngestionWorker worker;

    @Autowired
    DocumentRepository documents;

    @Autowired
    JdbcTemplate jdbc;

    private final String account = "acct-docling-" + UUID.randomUUID();
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws InterruptedException {
        while (worker.isPaused()) Thread.sleep(50);
        jdbc.update("DELETE FROM document.document");
        TestEmbeddingModel.INSTANCE.reset();
        status = 200;
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        appender.stop();
        status = 200;
    }

    @Test
    void runOnce_whenTheSidecarConverts_storesItsPagesAndKeepsTheTableInOneChunk() {
        UUID id = upload(TestPdfs.pages("Hiz siniri", "Ikinci sayfa."), "isg.pdf");

        assertThat(worker.runOnce()).isOne();

        assertThat(documents.find(account, id).orElseThrow().status().name()).isEqualTo("READY");
        List<String> pages = jdbc.queryForList(
                "SELECT content FROM document.document_page WHERE document_id = ? ORDER BY page_number", String.class, id);
        assertThat(pages).hasSize(2);
        assertThat(pages.getFirst()).contains("| Alan | Azami hız |\n| --- | --- |\n| Raf koridorları | 6 km/sa |");
        List<Map<String, Object>> chunks = jdbc.queryForList(
                "SELECT page_number, content FROM document.document_chunk WHERE document_id = ? ORDER BY chunk_index", id);
        assertThat(chunks).anySatisfy(c -> {
            assertThat(c.get("page_number")).isEqualTo(1);
            assertThat((String) c.get("content")).isEqualTo("Hız sınırları " + MARKER
                    + "\n| Alan | Azami hız |\n| --- | --- |\n| Raf koridorları | 6 km/sa |");
        });
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("parser=DOCLING"));
        assertNoDocumentContentLogged();
    }

    @Test
    void runOnce_whenTheSidecarFails_isReadyWithTheJdkTextAndLogsTheReasonOnly() {
        status = 500;
        UUID id = upload(TestPdfs.pages(MARKER + " birinci sayfa."), MARKER + ".pdf");

        assertThat(worker.runOnce()).isOne();

        assertThat(documents.find(account, id).orElseThrow().status().name()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT content FROM document.document_page WHERE document_id = ?", String.class, id))
                .isEqualTo(MARKER + " birinci sayfa.");
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("Document parsed without the sidecar")
                && e.getFormattedMessage().contains("reason=UNAVAILABLE") && e.getLevel().toString().equals("WARN"));
        assertNoDocumentContentLogged();
    }

    /** OCR is the only way into a scanned PDF: while the sidecar is down it is retried later, not FAILED with NO_TEXT. */
    @Test
    void runOnce_whenAScannedPdfMeetsADownSidecar_retriesLater() {
        status = 503;
        UUID id = upload(TestPdfs.blank(2), "scan.pdf");

        assertThat(worker.runOnce()).isOne();

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, attempts, failure_reason, next_attempt_at > now() AS later FROM document.document WHERE id = ?", id);
        assertThat(job.get("status")).isEqualTo("PENDING");
        assertThat(job.get("failure_reason")).isNull();
        assertThat(job.get("later")).isEqualTo(true);
        assertNoDocumentContentLogged();
    }

    private void assertNoDocumentContentLogged() {
        for (ILoggingEvent event : appender.list) {
            StringBuilder text = new StringBuilder(event.getFormattedMessage()).append(event.getMDCPropertyMap());
            if (event.getArgumentArray() != null) Stream.of(event.getArgumentArray()).forEach(a -> text.append(' ').append(a));
            for (var p = event.getThrowableProxy(); p != null; p = p.getCause()) text.append(' ').append(p.getMessage());
            assertThat(text.toString()).as(event.getLoggerName()).doesNotContain(MARKER).doesNotContain(account);
        }
    }

    private UUID upload(byte[] file, String fileName) {
        DocumentRow row = documents.insert(account, fileName, DocumentFormat.PDF, file.length, null, Instant.now());
        documents.insertFile(row.id(), file);
        return row.id();
    }
}
