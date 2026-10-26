package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.worker.IngestionWorker;
import com.verso.document.worker.SystemModelBackfillJob;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.AuditTables;
import com.verso.support.Multipart;
import com.verso.support.QaTables;
import com.verso.support.TestChatModel;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The light system model end to end (ADR-0054) with the real database, the real ingestion worker and stub models:
 * ingestion extracts it (no person ever becomes an entity), deleting or re-ingesting a document removes or replaces
 * it, the lookup API filters by the caller's principals, the knowledge-health report gets the three structural
 * signals, every admin endpoint is verso-admin only, the suggestion pass is off by default and reviews are audited
 * with ids only, and no document text reaches a log line.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"management.server.port=0"})
@VersoTestEnvironment
class SystemModelApiTest {

    private static final String DEPO = """
            İade Süreci
            Sorumlu: Depo Sorumlusu Ahmet Yılmaz
            1. WMS > Stok > İade Girişi menüsünden kayıt açılır.
            2. Kalite Uzmanı tarafından kontrol edilir.

            Hata kodları
            WMS-204: Stok yetersiz MarkerSysDepo
            FEFO (First Expired First Out) kuralı uygulanır.

            Sayım Süreci
            1. Raf kilitlenir.
            2. Sayım yapılır.
            """;
    private static final String IK = """
            Sevkiyat notları
            FEFO (First Ended First Out) MarkerSysIk ile çalışılır.
            Ekranda WMS-310 görülürse depo aranır.
            """;

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestionWorker worker;

    @Autowired
    SystemModelBackfillJob backfill;

    @Autowired
    ModelCircuitBreaker circuit;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String adminSub = "acct-sysmap-admin-" + suffix;
    private final String admin = TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    /** An admin without any grant: manages, but reads no collection (ADR-0028). */
    private final String blindAdmin = TestIdp.IDP.token().subject("acct-sysmap-blind-" + suffix)
            .realmRoles("verso-admin").build();
    private final String depoUser = token("acct-sysmap-depo-" + suffix, "/depo");
    private final String ikUser = token("acct-sysmap-ik-" + suffix, "/ik");
    private final String outsider = token("acct-sysmap-out-" + suffix);
    private Logger root;
    private ListAppender<ILoggingEvent> appender;
    private UUID depo;
    private UUID ik;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection");
        QaTables.reset();
        AuditTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
        TestChatModel.INSTANCE.reset();
        circuit.reset();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (worker.isPaused()) {
            assertThat(System.nanoTime()).as("ingestion worker stays paused").isLessThan(deadline);
            Thread.sleep(50);
        }
        depo = collection("depo " + suffix);
        ik = collection("ik " + suffix);
        grant(depo, "GROUP", "/depo");
        grant(ik, "GROUP", "/ik");
        grant(depo, "USER", adminSub);
        grant(ik, "USER", adminSub);
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
    void ingestion_extractsTheModel_boundToChunkHashes_withTitlesButNoPersonNames() throws Exception {
        UUID document = uploadText(depo, "depo.txt", DEPO);
        process();

        List<String> keys = jdbc.queryForList("SELECT type || ':' || key FROM document.system_entity "
                + "WHERE document_id = ?", String.class, document);
        assertThat(keys).contains("CODE:WMS-204", "TERM:FEFO", "SYSTEM:WMS", "PROCESS:iade sureci",
                "PROCESS:sayim sureci", "ROLE:depo sorumlusu", "ROLE:kalite uzmani",
                "SCREEN:wms > stok > iade girisi", "STEP:iade sureci#2");
        assertThat(jdbc.queryForList("SELECT label FROM document.system_entity WHERE document_id = ?", String.class,
                document)).noneMatch(label -> label.contains("Ahmet") || label.contains("Yılmaz"));
        // Every mention carries the hash of the chunk it was read from.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM document.system_mention m JOIN document.document_chunk c ON c.id = m.chunk_id
                WHERE m.document_id = ? AND m.chunk_sha256 <> encode(sha256(convert_to(c.content, 'UTF8')), 'hex')""",
                Long.class, document)).isZero();
        assertThat(jdbc.queryForObject("SELECT extractor_version FROM document.system_model_state WHERE document_id = ?",
                Integer.class, document)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.system_relation WHERE document_id = ? "
                + "AND type = 'OWNED_BY'", Long.class, document)).isGreaterThanOrEqualTo(2);
        assertNoContentLogged();
    }

    @Test
    void deleteAndReingest_removeOrReplaceTheDocumentsModel_andBackfillTakesOnlyWhatIsMissing() throws Exception {
        UUID kept = uploadText(depo, "depo.txt", DEPO);
        UUID removed = uploadText(ik, "ik.txt", IK);
        process();
        List<UUID> before = jdbc.queryForList("SELECT id FROM document.system_entity WHERE document_id = ?", UUID.class,
                kept);

        assertThat(send(admin, "DELETE", "/v1/documents/" + removed, null).statusCode()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM document.system_entity WHERE document_id = ?", removed)).isZero();
        assertThat(count("SELECT count(*) FROM document.system_model_state WHERE document_id = ?", removed)).isZero();

        // Re-ingestion of the same document: new chunks, so the model is dropped and extracted again from them.
        jdbc.update("UPDATE document.document SET status = 'PENDING', next_attempt_at = now() WHERE id = ?", kept);
        process();
        List<UUID> after = jdbc.queryForList("SELECT id FROM document.system_entity WHERE document_id = ?", UUID.class,
                kept);
        assertThat(after).hasSameSizeAs(before).doesNotContainAnyElementsOf(before);
        assertThat(count("""
                SELECT count(*) FROM document.system_mention m LEFT JOIN document.document_chunk c ON c.id = m.chunk_id
                WHERE m.document_id = ? AND c.id IS NULL""", kept)).isZero();

        // Nothing is pending: the backfill takes nothing; a document without a model is taken, alone.
        assertThat(backfill.runOnce()).isZero();
        jdbc.update("DELETE FROM document.system_entity WHERE document_id = ?", kept);
        jdbc.update("DELETE FROM document.system_model_state WHERE document_id = ?", kept);
        assertThat(backfill.runOnce()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM document.system_entity WHERE document_id = ?", kept))
                .isEqualTo(before.size());
    }

    @Test
    void backfill_whenTwoInstancesRunAtOnce_extractsEveryDocumentOnce() throws Exception {
        UUID first = uploadText(depo, "depo.txt", DEPO);
        UUID second = uploadText(ik, "ik.txt", IK);
        process();
        jdbc.update("DELETE FROM document.system_entity");
        jdbc.update("DELETE FROM document.system_model_state");

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(backfill::runOnce);
            var b = pool.submit(backfill::runOnce);
            // SKIP LOCKED: the two runs take different rows; together they take both, none twice.
            assertThat(a.get(60, TimeUnit.SECONDS) + b.get(60, TimeUnit.SECONDS)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        for (UUID document : List.of(first, second)) {
            assertThat(count("SELECT count(*) FROM document.system_model_state WHERE document_id = ?", document))
                    .isEqualTo(1);
        }
        assertThat(backfill.runOnce()).isZero();
    }

    @Test
    void lookup_returnsOnlyWhatTheCallerMayRead_andRefusesABadQuery() throws Exception {
        uploadText(depo, "depo.txt", DEPO);
        uploadText(ik, "ik.txt", IK);
        process();

        assertThat(definitions(depoUser, "fefo")).containsExactly("First Expired First Out");
        assertThat(definitions(ikUser, "FEFO")).containsExactly("First Ended First Out");
        assertThat(definitions(admin, "fefo")).containsExactlyInAnyOrder("First Expired First Out",
                "First Ended First Out");
        HttpResponse<String> none = get(outsider, "/v1/system-model/lookup?q=FEFO");
        assertThat(none.statusCode()).isEqualTo(200);
        assertThat(none.body()).isEqualTo("[]");
        assertThat(none.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        // A code, folded: the IK user does not learn that the Depo collection defines WMS-204.
        assertThat(definitions(depoUser, "wms-204")).containsExactly("Stok yetersiz MarkerSysDepo");
        assertThat(get(ikUser, "/v1/system-model/lookup?q=WMS-204").body()).isEqualTo("[]");

        assertError(get(depoUser, "/v1/system-model/lookup?q=%20"), 400, 10051);
        assertError(get(depoUser, "/v1/system-model/lookup?q=" + "a".repeat(201)), 400, 10051);
        assertThat(http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/system-model/lookup?q=FEFO")).GET()
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertNoContentLogged();
    }

    @Test
    void systemMap_isAdminOnly_andShowsOnlyWhatTheAdminMayRead() throws Exception {
        UUID document = uploadText(depo, "depo.txt", DEPO);
        process();

        for (String path : List.of("/v1/admin/system-map/entities", "/v1/admin/system-map/stats",
                "/v1/admin/system-map/entity?type=CODE&key=WMS-204", "/v1/admin/system-map/suggestions",
                "/v1/admin/system-map/suggestions/settings")) {
            assertThat(get(depoUser, path).statusCode()).as(path).isEqualTo(403);
            assertThat(get(admin, path).statusCode()).as(path).isEqualTo(200);
        }
        for (String path : List.of("/v1/admin/system-map/suggestions/documents/" + document,
                "/v1/admin/system-map/suggestions/" + UUID.randomUUID() + "/accept",
                "/v1/admin/system-map/suggestions/" + UUID.randomUUID() + "/reject")) {
            assertThat(send(depoUser, "POST", path, null).statusCode()).as(path).isEqualTo(403);
        }

        HttpResponse<String> systems = get(admin, "/v1/admin/system-map/entities?type=SYSTEM");
        assertThat((List<String>) JsonPath.read(systems.body(), "$[*].key")).contains("WMS");
        HttpResponse<String> wms = get(admin, "/v1/admin/system-map/entity?type=SYSTEM&key=WMS");
        assertThat(wms.statusCode()).as(wms.body()).isEqualTo(200);
        assertThat((List<String>) JsonPath.read(wms.body(), "$.links[*].key")).contains("WMS-204",
                "wms > stok > iade girisi");
        HttpResponse<String> code = get(admin, "/v1/admin/system-map/entity?type=CODE&key="
                + URLEncoder.encode("WMS-204", StandardCharsets.UTF_8));
        assertThat((List<String>) JsonPath.read(code.body(), "$.sources[*].documentId")).containsOnly(document.toString());
        HttpResponse<String> process = get(admin, "/v1/admin/system-map/entity?type=PROCESS&key="
                + URLEncoder.encode("iade sureci", StandardCharsets.UTF_8));
        assertThat((List<String>) JsonPath.read(process.body(), "$.links[?(@.relation == 'OWNED_BY')].label"))
                .contains("Depo Sorumlusu");
        assertThat((Integer) JsonPath.read(get(admin, "/v1/admin/system-map/stats").body(), "$.documents")).isEqualTo(1);

        // The blind admin manages but reads nothing: the entity does not exist for it.
        assertError(get(blindAdmin, "/v1/admin/system-map/entity?type=CODE&key=WMS-204"), 404, 10050);
        assertThat(get(blindAdmin, "/v1/admin/system-map/entities").body()).isEqualTo("[]");
        assertNoContentLogged();
    }

    @Test
    void knowledgeHealth_getsTheStructuralSignals_forTheAdminsReadableSharedDocuments() throws Exception {
        uploadText(depo, "depo.txt", DEPO);
        uploadText(ik, "ik.txt", IK);
        process();

        HttpResponse<String> report = get(admin, "/v1/admin/knowledge-health?from=" + today().minusDays(30) + "&to="
                + today());
        assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
        assertThat((List<String>) JsonPath.read(report.body(), "$.systemModel[?(@.kind == 'UNDEFINED_CODE')].key"))
                .contains("WMS-310").doesNotContain("WMS-204");
        assertThat((List<String>) JsonPath.read(report.body(), "$.systemModel[?(@.kind == 'TERM_CONFLICT')].key"))
                .containsExactly("FEFO");
        assertThat((List<String>) JsonPath.read(report.body(),
                "$.systemModel[?(@.kind == 'PROCESS_WITHOUT_OWNER')].key")).contains("sayim sureci")
                .doesNotContain("iade sureci");
        assertThat((List<String>) JsonPath.read(report.body(), "$.findings[*].code"))
                .contains("TERM_CONFLICTS", "UNDEFINED_CODES", "PROCESSES_WITHOUT_OWNER");

        HttpResponse<String> blind = get(blindAdmin, "/v1/admin/knowledge-health?from=" + today().minusDays(30)
                + "&to=" + today());
        assertThat((List<?>) JsonPath.read(blind.body(), "$.systemModel")).isEmpty();
        assertNoContentLogged();
    }

    @Test
    void suggestions_areOffByDefault_andAReviewIsAuditedWithIdsOnly() throws Exception {
        UUID document = uploadText(depo, "depo.txt", DEPO);
        process();

        assertThat((Boolean) JsonPath.read(get(admin, "/v1/admin/system-map/suggestions/settings").body(), "$.enabled"))
                .isFalse();
        assertError(send(admin, "POST", "/v1/admin/system-map/suggestions/documents/" + document, null), 409, 11080);
        assertThat(TestChatModel.INSTANCE.calls()).isZero();

        // A stored DRAFT (as the pass would write it) bound to a real chunk: listed, reviewed once, audited.
        Map<String, Object> chunk = jdbc.queryForMap("SELECT id, encode(sha256(convert_to(content, 'UTF8')), 'hex') "
                + "AS hash FROM document.document_chunk WHERE document_id = ? ORDER BY chunk_index LIMIT 1", document);
        QaTables.update("""
                INSERT INTO qa.system_model_suggestion (document_id, chunk_id, chunk_sha256, relation, source_type,
                    source_key, target_type, target_key, created_by)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, 'OWNED_BY', 'PROCESS', 'sayim sureci', 'ROLE',
                    'depo sorumlusu', ?)""", document.toString(), chunk.get("id").toString(), chunk.get("hash"),
                adminSub);
        HttpResponse<String> drafts = get(admin, "/v1/admin/system-map/suggestions");
        List<String> ids = JsonPath.read(drafts.body(), "$[*].id");
        assertThat(ids).hasSize(1);
        assertThat(get(blindAdmin, "/v1/admin/system-map/suggestions").body()).isEqualTo("[]");
        assertError(send(blindAdmin, "POST", "/v1/admin/system-map/suggestions/" + ids.getFirst() + "/accept", null),
                404, 11081);

        HttpResponse<String> accepted = send(admin, "POST", "/v1/admin/system-map/suggestions/" + ids.getFirst()
                + "/accept", null);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(accepted.body(), "$.state")).isEqualTo("ACCEPTED");
        assertError(send(admin, "POST", "/v1/admin/system-map/suggestions/" + ids.getFirst() + "/reject", null),
                409, 11082);
        assertThat(AuditTables.count("select count(*) from audit.event where action = 'SYSTEM_SUGGESTION_ACCEPTED'"))
                .isEqualTo(1);
        assertThat(AuditTables.count("select count(*) from audit.event e where row_to_json(e)::text like '%sayim%'"))
                .isZero();

        // Deleting the document removes its suggestions through the document outbox.
        assertThat(send(admin, "DELETE", "/v1/documents/" + document, null).statusCode()).isEqualTo(204);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (QaTables.count("select count(*) from qa.system_model_suggestion") != 0) {
            assertThat(System.nanoTime()).as("suggestions deleted").isLessThan(deadline);
            Thread.sleep(100);
        }
        assertNoContentLogged();
    }

    // ---------------------------------------------------------------- helpers

    private List<String> definitions(String token, String query) throws Exception {
        HttpResponse<String> response = get(token, "/v1/system-model/lookup?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$[*].definitions[*].value");
    }

    private long count(String sql, UUID id) {
        return jdbc.queryForObject(sql, Long.class, id);
    }

    private void assertNoContentLogged() {
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.contains("MarkerSys") || line.contains("First Expired")
                        || line.contains("Ahmet") || line.contains("Stok yetersiz") || line.contains("iade sureci"));
    }

    private static void assertError(HttpResponse<String> response, int status, int code) {
        assertThat(response.statusCode()).as(response.request().method() + " " + response.request().uri() + " "
                + response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":" + code);
    }

    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Europe/Istanbul"));
    }

    private static String token(String subject, String... groups) {
        var builder = TestIdp.IDP.token().subject(subject);
        if (groups.length > 0) builder.claim("groups", List.of(groups));
        return builder.build();
    }

    private UUID collection(String name) throws Exception {
        HttpResponse<String> response = send(admin, "POST", "/v1/collections", "{\"name\":\"" + name + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private void grant(UUID collection, String type, String principal) throws Exception {
        HttpResponse<String> response = send(admin, "POST", "/v1/collections/" + collection + "/grants",
                "{\"principalType\":\"" + type + "\",\"principal\":\"" + principal + "\"}");
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
    }

    private UUID uploadText(UUID collection, String fileName, String text) throws Exception {
        Multipart body = Multipart.file("file", fileName, "text/plain", text.getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/documents?collectionId="
                        + collection)).header("Authorization", "Bearer " + admin).header("Content-Type", body.contentType())
                .timeout(Duration.ofSeconds(30)).POST(body.body()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private void process() {
        for (int i = 0; i < 5; i++) worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document WHERE status <> 'READY'", Integer.class))
                .isZero();
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return send(token, "GET", path, null);
    }

    private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(60));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
