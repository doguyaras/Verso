package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.testing.TestPdfs;
import com.verso.support.AuditTables;
import com.verso.support.Multipart;
import com.verso.support.QaTables;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
 * The activity centre (ADR-0058) against the real modules: GET /v1/admin/activity, /summary and /{id} are verso-admin
 * only; every module reports through its own source; names follow the admin's grants (ADR-0028); no response and no
 * log line carries an account id; pages are capped. Also GET /v1/home for any user and /v1/info's stale threshold.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class AdminActivityApiTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String adminSub = "acct-activity-admin-" + suffix;
    private final String admin = TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    /** An admin without any grant: sees the runs, but no collection's documents (ADR-0028). */
    private final String blindSub = "acct-activity-blind-" + suffix;
    private final String blindAdmin = TestIdp.IDP.token().subject(blindSub).realmRoles("verso-admin").build();
    private final String deleterSub = "acct-activity-deleter-" + suffix;
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection");
        QaTables.reset();
        AuditTables.reset();
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

    /** ADR-0019: a user, an operator and a foreign role get 403 ACCESS_DENIED on every path; anonymous 401. */
    @Test
    void activity_whenCallerIsNotAnAdmin_isDenied() throws Exception {
        String user = TestIdp.IDP.tokenFor("acct-activity-user-" + suffix);
        String operator = TestIdp.IDP.token().subject("acct-activity-op-" + suffix).realmRoles("verso-operator").build();
        String other = TestIdp.IDP.token().subject("acct-activity-other-" + suffix).realmRoles("some-role").build();
        for (String path : List.of("/v1/admin/activity", "/v1/admin/activity/summary",
                "/v1/admin/activity/qa." + UUID.randomUUID())) {
            for (String token : List.of(user, operator, other)) {
                HttpResponse<String> denied = get(token, path);
                assertThat(denied.statusCode()).as(path).isEqualTo(403);
                assertThat(denied.body()).contains("\"code\":90101").doesNotContain("items");
            }
            assertThat(get(null, path).statusCode()).as(path).isEqualTo(401);
        }
    }

    @Test
    void activity_mergesEveryModule_namesOnlyWhatTheAdminReads_andCarriesNoAccountId() throws Exception {
        UUID collection = collection("bt destek " + suffix);
        grant(collection, "USER", adminSub);
        uploadInto(collection, "vpn-kilavuzu-" + suffix + ".pdf");
        QaTables.update("INSERT INTO qa.eval_run (run_trigger, run_day, status, top_k, cases, found, missed, "
                + "recall_at_k, baseline_recall, alert, started_at, finished_at) VALUES ('SCHEDULED', current_date, "
                + "'SUCCEEDED', 5, 152, 148, 4, 0.9737, 0.9934, true, now() - interval '2 hours', now() - interval '1 hour')");
        QaTables.update("INSERT INTO qa.diagnostic_run (run_trigger, run_day, status, window_from, window_to, "
                + "min_askers, questions, clusters, contradictions_checked, started_at, finished_at) VALUES ('MANUAL', "
                + "current_date, 'SUCCEEDED', current_date - 29, current_date, 5, 40, 3, 12, now() - interval '3 hours', "
                + "now() - interval '3 hours')");
        QaTables.update("INSERT INTO qa.hook_notification (rule_key, action, trigger_type, subject_type, subject_id, "
                + "detail, severity, dedupe_key) VALUES ('CARD_STALE_NOTIFY', 'NOTIFY', 'CARD_STALE', 'KNOWLEDGE_CARD', "
                + "gen_random_uuid(), 'SOURCE_CHANGED', 'WARNING', 'activity-test-" + suffix + "')");
        QaTables.update("INSERT INTO qa.contradiction_candidate (chunk_a, document_a, page_a, hash_a, chunk_b, "
                + "document_b, page_b, hash_b, similarity, differences) VALUES ('00000000-0000-0000-0000-000000000001', "
                + "gen_random_uuid(), 1, repeat('a', 64), '00000000-0000-0000-0000-000000000002', gen_random_uuid(), 2, "
                + "repeat('b', 64), 0.95, 1)");
        AuditTables.insertAt("now", deleterSub, "DOCUMENT_DELETED");

        HttpResponse<String> response = get(admin, "/v1/admin/activity?size=50");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        String body = response.body();
        assertThat(JsonPath.<List<String>>read(body, "$.sources")).contains("qa", "connector", "document", "audit", "work");
        // A failing source logs "Activity source unavailable: source=… reason=<exception type>" at WARN; show it here.
        assertThat(JsonPath.<Boolean>read(body, "$.partial"))
                .as("no source may fail; warnings: %s", appender.list.stream()
                        .filter(e -> e.getFormattedMessage().startsWith("Activity source unavailable"))
                        .map(ILoggingEvent::getFormattedMessage).toList())
                .isFalse();
        List<String> types = JsonPath.read(body, "$.items[*].type");
        assertThat(types).contains("GOLDEN_EVAL", "KNOWLEDGE_HEALTH", "HOOK_ACTION", "CONTRADICTION", "DOCUMENTS_ADDED",
                "DOCUMENTS_DELETED");
        assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.type == 'GOLDEN_EVAL')].status"))
                .containsExactly("REGRESSION");
        assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.type == 'DOCUMENTS_ADDED')].detail").getFirst())
                .contains("vpn-kilavuzu-" + suffix);
        assertThat(JsonPath.<List<String>>read(body, "$.items[?(@.type == 'CONTRADICTION')].title"))
                .containsExactly("Belgeler arasında çelişki");
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].actor.kind")).allMatch(k -> List.of("SYSTEM", "ROLE",
                "TEAM").contains(k));
        assertThat(body).doesNotContain(adminSub).doesNotContain(deleterSub);

        // The admin without a grant: the same runs, no collection, no file name (ADR-0028).
        String blind = get(blindAdmin, "/v1/admin/activity?size=50").body();
        assertThat(JsonPath.<List<String>>read(blind, "$.items[*].type")).contains("GOLDEN_EVAL")
                .doesNotContain("DOCUMENTS_ADDED");
        assertThat(blind).doesNotContain("vpn-kilavuzu");

        // Filters: a category, a collection.
        assertThat(JsonPath.<List<String>>read(get(admin, "/v1/admin/activity?category=APPROVAL").body(),
                "$.items[*].type")).containsExactly("CONTRADICTION");
        assertThat(JsonPath.<List<String>>read(get(admin, "/v1/admin/activity?collection=" + collection).body(),
                "$.items[*].type")).containsExactly("DOCUMENTS_ADDED");

        // The drawer of the golden-set run: facts, no account id.
        String evalId = JsonPath.<List<String>>read(body, "$.items[?(@.type == 'GOLDEN_EVAL')].id").getFirst();
        HttpResponse<String> detail = get(admin, "/v1/admin/activity/" + evalId);
        assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(detail.body(), "$.facts[*].label")).contains("Vaka", "Bulunan");
        assertThat(JsonPath.<String>read(detail.body(), "$.item.audit.targetType")).isEqualTo("KNOWLEDGE_EVAL");

        // The status strip: the regression and the contradiction need a person.
        String summary = get(admin, "/v1/admin/activity/summary").body();
        assertThat(JsonPath.<Integer>read(summary, "$.attention")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(summary, "$.pendingApprovals")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(summary, "$.reasons")).containsExactly("Altın değerlendirmede gerileme",
                "1 onay bekliyor");

        // No log line names an account or a file.
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(adminSub)
                || e.getFormattedMessage().contains(deleterSub) || e.getFormattedMessage().contains("vpn-kilavuzu-" + suffix));
    }

    @Test
    void activity_refusesFiltersBeyondTheCaps_andUnknownIds() throws Exception {
        for (String query : List.of("size=51", "size=0", "page=10&size=50", "page=-1", "status=DONE", "type=lower",
                "category=NOPE", "from=2026-10-20&to=2026-10-10", "from=2026-01-01&to=2026-10-10")) {
            HttpResponse<String> response = get(admin, "/v1/admin/activity?" + query);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(response.body()).as(query).contains("\"code\":11110");
        }
        for (String id : List.of("qa." + UUID.randomUUID(), "skills." + UUID.randomUUID(), "qa.nope", "nothing")) {
            HttpResponse<String> response = get(admin, "/v1/admin/activity/" + id);
            assertThat(response.statusCode()).as(id).isEqualTo(404);
            assertThat(response.body()).as(id).contains("\"code\":11111");
        }
        HttpResponse<String> ok = get(admin, "/v1/admin/activity?page=9&size=50");
        assertThat(ok.statusCode()).as("the deepest allowed page").isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(ok.body(), "$.hasMore")).isFalse();
    }

    @Test
    void home_isForEverySignedInCaller_andInfoStatesTheStaleThreshold() throws Exception {
        String user = TestIdp.IDP.tokenFor("acct-activity-home-" + suffix);
        HttpResponse<String> home = get(user, "/v1/home");
        assertThat(home.statusCode()).as(home.body()).isEqualTo(200);
        assertThat(JsonPath.<List<Object>>read(home.body(), "$.outputs")).isEmpty();
        assertThat(get(null, "/v1/home").statusCode()).isEqualTo(401);
        HttpResponse<String> info = get(user, "/v1/info");
        assertThat(JsonPath.<Integer>read(info.body(), "$.staleAfterDays")).isEqualTo(180);
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

    private void uploadInto(UUID collection, String fileName) throws Exception {
        Multipart body = Multipart.pdf(fileName, TestPdfs.pages("VPN kurulum adimlari."));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/documents?collectionId="
                        + collection)).header("Authorization", "Bearer " + admin).header("Content-Type", body.contentType())
                .timeout(Duration.ofSeconds(30)).POST(body.body()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
