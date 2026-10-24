package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.verso.support.AuditTables;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * GET /v1/admin/audit and /export (ADR-0026) over HTTP with the real database: verso-admin only (ADR-0019), a size
 * cap, a row cap on the export, no-store, and a CSV that a spreadsheet cannot turn into a formula. The export cap is
 * lowered to 5 so the cut is cheap to show.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.audit.export-max-rows=5", "verso.audit.export-batch-size=2"})
@VersoTestEnvironment
class AuditApiTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String user = "acct-audit-user-" + UUID.randomUUID();
    private final String adminSub = "acct-audit-admin-" + UUID.randomUUID();
    private final String admin = "Bearer " + TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();

    @BeforeEach
    void setUp() {
        AuditTables.reset();
    }

    /** ADR-0019: a signed-in user without verso-admin gets 403 ACCESS_DENIED, an anonymous caller 401. */
    @Test
    void audit_whenCallerIsNotAnAdmin_isDeniedOnBothEndpoints() throws Exception {
        AuditTables.insertAt("now", "actor-seen-by-nobody", "DOCUMENT_UPLOADED");
        for (String path : new String[]{"/v1/admin/audit?from=" + today() + "&to=" + today(),
                "/v1/admin/audit/export?from=" + today() + "&to=" + today()}) {
            HttpResponse<String> denied = get(TestIdp.bearer(user), path);
            assertThat(denied.statusCode()).as(path).isEqualTo(403);
            assertThat(denied.body()).contains("\"code\":90101").doesNotContain("actor-seen-by-nobody");
            assertThat(get(null, path).statusCode()).as(path + " anonymous").isEqualTo(401);
        }
        String operator = TestIdp.IDP.token().subject("acct-operator").realmRoles("verso-operator", "verso-user").build();
        assertThat(get("Bearer " + operator, "/v1/admin/audit?from=" + today() + "&to=" + today()).statusCode())
                .as("verso-operator is no admin").isEqualTo(403);
    }

    @Test
    void list_whenAdmin_returnsTheWindowNewestFirstWithItsFiltersAndPages() throws Exception {
        AuditTables.insertAt("now", "actor-a", "DOCUMENT_UPLOADED");
        AuditTables.insertAt("now", "actor-b", "DOCUMENT_DELETED");
        AuditTables.insertAt("now", "actor-c", "DOCUMENT_UPLOADED");

        HttpResponse<String> all = get(admin, "/v1/admin/audit?from=" + today().minusDays(1) + "&to=" + today().plusDays(1));

        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(all.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat((Integer) JsonPath.read(all.body(), "$.page.totalElements")).isEqualTo(3);
        assertThat((List<String>) JsonPath.read(all.body(), "$.data[*].actor")).containsExactly("actor-c", "actor-b", "actor-a");
        assertThat((String) JsonPath.read(all.body(), "$.data[0].action")).isEqualTo("DOCUMENT_UPLOADED");
        assertThat((String) JsonPath.read(all.body(), "$.data[0].outcome")).isEqualTo("SUCCESS");
        assertThat((String) JsonPath.read(all.body(), "$.data[0].mode")).isEqualTo("local");
        assertThat(all.body()).contains("\"occurredAt\"", "\"traceId\"", "\"targetType\"", "\"actorRoles\"");

        HttpResponse<String> deleted = get(admin, "/v1/admin/audit?from=" + today() + "&to=" + today()
                + "&action=DOCUMENT_DELETED");
        assertThat((List<String>) JsonPath.read(deleted.body(), "$.data[*].actor")).containsExactly("actor-b");

        HttpResponse<String> second = get(admin, "/v1/admin/audit?from=" + today() + "&to=" + today() + "&size=2&page=1");
        assertThat((List<String>) JsonPath.read(second.body(), "$.data[*].actor")).containsExactly("actor-a");
        assertThat((Integer) JsonPath.read(second.body(), "$.page.totalPages")).isEqualTo(2);

        HttpResponse<String> past = get(admin, "/v1/admin/audit?from=" + today().minusDays(10) + "&to="
                + today().minusDays(5));
        assertThat((Integer) JsonPath.read(past.body(), "$.page.totalElements")).isZero();
    }

    /** The page size is capped (200); the window is bounded; an unknown action is a client error, never a 500. */
    @Test
    void list_whenParametersAreOutOfBounds_isRejectedWith400() throws Exception {
        String window = "from=" + today() + "&to=" + today();
        assertThat(get(admin, "/v1/admin/audit?" + window + "&size=200").statusCode()).as("size 200").isEqualTo(200);
        for (String bad : new String[]{"&size=201", "&size=0", "&size=-1", "&page=-1", "&page=10001", "&action=DROP_TABLE",
                "&size=abc"}) {
            HttpResponse<String> response = get(admin, "/v1/admin/audit?" + window + bad);
            assertThat(response.statusCode()).as(bad).isEqualTo(400);
            assertThat(response.body()).as(bad).contains("\"ok\":false");
        }
        HttpResponse<String> reversed = get(admin, "/v1/admin/audit?from=" + today() + "&to=" + today().minusDays(1));
        assertThat(reversed.statusCode()).isEqualTo(400);
        assertThat(reversed.body()).contains("\"code\":12001");
        HttpResponse<String> tooLong = get(admin, "/v1/admin/audit?from=" + today().minusDays(366) + "&to=" + today());
        assertThat(tooLong.statusCode()).as("367 days").isEqualTo(400);
        assertThat(get(admin, "/v1/admin/audit?from=" + today().minusDays(365) + "&to=" + today()).statusCode())
                .as("366 days").isEqualTo(200);
        assertThat(get(admin, "/v1/admin/audit?from=2026-13-01&to=" + today()).statusCode()).as("bad date").isEqualTo(400);
        assertThat(get(admin, "/v1/admin/audit").statusCode()).as("no window").isEqualTo(400);
        assertThat(get(admin, "/v1/admin/audit/export?from=" + today() + "&to=" + today().minusDays(1)).statusCode())
                .as("export reversed").isEqualTo(400);
    }

    @Test
    void export_whenAdmin_streamsCsvOldestFirstWithNoStore() throws Exception {
        AuditTables.insertAt("2026-01-05T10:00:00Z", "actor-1", "DOCUMENT_UPLOADED");
        AuditTables.insertAt("2026-01-05T11:00:00Z", "actor-2", "DOCUMENT_DELETED");
        AuditTables.insertAt("2026-01-06T09:00:00Z", "actor-3", "QUESTION_ASKED");
        AuditTables.insertAt("2026-01-09T09:00:00Z", "actor-outside", "QUESTION_ASKED");

        HttpResponse<String> csv = get(admin, "/v1/admin/audit/export?from=2026-01-05&to=2026-01-06");

        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv").containsIgnoringCase("utf-8");
        assertThat(csv.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat(csv.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"audit-2026-01-05_2026-01-06.csv\"");
        assertThat(csv.headers().firstValue("X-Audit-Export-Truncated")).hasValue("false");
        List<String> lines = Arrays.asList(csv.body().split("\r\n"));
        assertThat(lines.get(0)).isEqualTo("id,occurred_at,actor,actor_roles,action,outcome,target_type,target_id,"
                + "conversation_id,detail_code,model_id,citation_document_ids,trace_id,mode");
        assertThat(lines).hasSize(4);
        assertThat(lines.get(1)).contains(",actor-1,").contains("2026-01-05T10:00:00Z");
        assertThat(lines.get(2)).contains(",actor-2,");
        assertThat(lines.get(3)).contains(",actor-3,");
        assertThat(csv.body()).doesNotContain("actor-outside");
    }

    /** The export stops at verso.audit.export-max-rows and says so before the first row (batches of 2 here). */
    @Test
    void export_whenTheWindowHoldsMoreThanTheCap_isCutAndSaysSo() throws Exception {
        for (int i = 0; i < 7; i++) AuditTables.insertAt("2026-02-0" + (i + 1) + "T10:00:00Z", "actor-" + i, "DOCUMENT_UPLOADED");

        HttpResponse<String> csv = get(admin, "/v1/admin/audit/export?from=2026-02-01&to=2026-02-28");

        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("X-Audit-Export-Truncated")).hasValue("true");
        assertThat(csv.headers().firstValue("X-Audit-Export-Max-Rows")).hasValue("5");
        List<String> lines = Arrays.asList(csv.body().split("\r\n"));
        assertThat(lines).as("header + 5 rows").hasSize(6);
        assertThat(lines.get(1)).contains("actor-0");
        assertThat(lines.get(5)).contains("actor-4");

        for (int i = 0; i < 2; i++) AuditTables.update("delete from audit.event where actor_sub = ?", "actor-" + (5 + i));
        assertThat(get(admin, "/v1/admin/audit/export?from=2026-02-01&to=2026-02-28").headers()
                .firstValue("X-Audit-Export-Truncated")).as("exactly at the cap is not a cut").hasValue("false");
    }

    /** OWASP CSV injection: a cell that starts with = + - @ (or a tab or CR) never reaches the file as a formula. */
    @Test
    void export_whenACellStartsLikeAFormula_isPrefixedAndQuotedSafely() throws Exception {
        String[] actors = {"=HYPERLINK(\"http://x\")", "+1+1", "-2+3", "@SUM(A1)", "\t=1+1"};
        for (int i = 0; i < actors.length; i++) {
            AuditTables.insertAt("2026-03-01T0" + i + ":00:00Z", actors[i], "DOCUMENT_UPLOADED");
        }

        String body = get(admin, "/v1/admin/audit/export?from=2026-03-01&to=2026-03-01").body();

        assertThat(body).contains("\"'=HYPERLINK(\"\"http://x\"\")\"").contains(",'+1+1,").contains(",'-2+3,")
                .contains(",'@SUM(A1),").contains(",'\t=1+1,");
        for (String line : body.split("\r\n")) {
            for (String cell : line.split(",")) {
                if (cell.isEmpty()) continue;
                assertThat(cell.charAt(0)).as("cell: " + cell).isNotIn('=', '+', '-', '@');
            }
        }
    }

    /** The denial itself is audited (who probed the admin endpoints), the 401 is not. */
    @Test
    void denial_whenAUserAsksAnAdminEndpoint_isRecordedWithoutPathOrParameters() throws Exception {
        get(TestIdp.bearer(user), "/v1/admin/audit?from=" + today() + "&to=" + today() + "&action=QUESTION_ASKED");
        get(null, "/v1/admin/audit?from=" + today() + "&to=" + today());

        assertThat(AuditTables.rows(user, "ACCESS_DENIED")).isOne();
        assertThat(AuditTables.count("select count(*) from audit.event where action = 'ACCESS_DENIED'")).as("401 not recorded").isOne();
        assertThat(AuditTables.strings("select outcome || '/' || target_type || '/' || detail_code from audit.event "
                + "where actor_sub = ?", user)).containsExactly("DENIED/ADMIN_API/ADMIN_AUDIT");
        assertThat(AuditTables.strings("select e::text from audit.event e")).allSatisfy(row ->
                assertThat(row).doesNotContain("QUESTION_ASKED").doesNotContain("/v1/admin"));
    }

    private HttpResponse<String> get(String authorization, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET()
                .timeout(Duration.ofSeconds(30));
        if (authorization != null) request.header("Authorization", authorization);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static LocalDate today() {
        return LocalDate.now(ZONE);
    }
}
