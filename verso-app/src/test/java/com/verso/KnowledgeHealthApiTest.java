package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository;
import com.verso.qa.service.HookEngine;
import com.verso.qa.service.KnowledgeDiagnostics;
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
import java.time.LocalDate;
import java.util.List;
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
 * The company diagnostics and hooks end to end (ADR-0052) with the real database, the real outboxes and stub models:
 * clusters appear only with at least 5 distinct askers and carry no question text unless the policy allows it; the run
 * row is the job's lock; contradiction values are shown only to an admin who reads both documents; hooks fire on a
 * document change and on a dispute; every endpoint is verso-admin only; audit rows hold ids only; no question or
 * document text reaches a log line.
 *
 * <p>Test embedding: texts of one "topic-x" are about 0.91 similar, other texts are unrelated. The contradiction scan's
 * similarity is lowered to 0.85 here so the two same-topic test chunks always qualify.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.qa.diagnostics.contradiction.similarity=0.85"})
@VersoTestEnvironment
class KnowledgeHealthApiTest {

    private static final String VPN = "topic-vpn MarkerHealthQ vpn baglantisi neden kopuyor";
    private static final String MAIL = "topic-mail MarkerHealthQ eposta kotasi neden doldu";
    private static final String LEAVE = "topic-leave MarkerHealthCard yıllık izin kaç gündür?";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestionWorker worker;

    @Autowired
    ModelCircuitBreaker circuit;

    @Autowired
    HookEngine hooks;

    @Autowired
    KnowledgeDiagnostics diagnostics;

    @Autowired
    KnowledgeDiagnosticsRepository diagnosticsRuns;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String adminSub = "acct-health-admin-" + suffix;
    private final String admin = TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    /** An admin without any grant: manages, but reads no collection (ADR-0028). */
    private final String blindAdmin = TestIdp.IDP.token().subject("acct-health-blind-" + suffix)
            .realmRoles("verso-admin").build();
    private final String user = token("acct-health-user-" + suffix, "/hukuk");
    private Logger root;
    private ListAppender<ILoggingEvent> appender;
    private UUID hukuk;
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
        hukuk = collection("hukuk " + suffix);
        ik = collection("ik " + suffix);
        grant(hukuk, "GROUP", "/hukuk");
        grant(ik, "GROUP", "/ik");
        grant(hukuk, "USER", adminSub);
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

    // ---------------------------------------------------------------- clusters and k-anonymity

    @Test
    void clusters_needFiveDistinctAskers_carryNoQuestionText_andTrendAcrossRuns() throws Exception {
        for (int i = 1; i <= 5; i++) assertNotFound(ask(token("acct-health-vpn" + i + "-" + suffix), VPN));
        // Four askers only: below k, never shown, whatever their number of questions.
        for (int i = 1; i <= 4; i++) {
            String mailUser = token("acct-health-mail" + i + "-" + suffix);
            assertNotFound(ask(mailUser, MAIL));
            assertNotFound(ask(mailUser, MAIL));
        }

        HttpResponse<String> run = send(admin, "POST", "/v1/admin/knowledge-health/runs", null);
        assertThat(run.statusCode()).as(run.body()).isEqualTo(201);
        assertThat((String) JsonPath.read(run.body(), "$.status")).isEqualTo("SUCCEEDED");
        assertThat((Integer) JsonPath.read(run.body(), "$.questions")).isEqualTo(13);
        assertThat((Integer) JsonPath.read(run.body(), "$.clusters")).isEqualTo(1);

        HttpResponse<String> report = report(admin);
        assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(report.body(), "$.minAskers")).isEqualTo(5);
        assertThat((Boolean) JsonPath.read(report.body(), "$.questionTextShown")).isFalse();
        assertThat((List<Object>) JsonPath.read(report.body(), "$.clusters")).hasSize(1);
        assertThat((Integer) JsonPath.read(report.body(), "$.clusters[0].askers")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(report.body(), "$.clusters[0].turns")).isEqualTo(5);
        assertThat((String) JsonPath.read(report.body(), "$.clusters[0].trend")).isEqualTo("NEW");
        assertThat((List<String>) JsonPath.read(report.body(), "$.clusters[0].keywords")).contains("vpn", "baglantisi")
                // Readable words as typed (not stems such as "baglantis"), each typed by at least 5 askers.
                .allMatch(word -> List.of(VPN.toLowerCase(java.util.Locale.ROOT).split("[^a-z]+")).contains(word));
        assertThat((List<String>) JsonPath.read(report.body(), "$.clusters[0].representativeQuestions")).isEmpty();
        assertThat((List<String>) JsonPath.read(report.body(), "$.findings[*].code")).contains("UNANSWERED_CLUSTER");
        // Policy off: no question text, and nothing of the 4-asker topic at all.
        assertThat(report.body()).doesNotContain("MarkerHealthQ").doesNotContain("eposta").doesNotContain("acct-health");
        // No account id is stored with a cluster; the database refuses a cluster below 5 askers.
        assertThat(QaTables.count("select count(*) from qa.question_cluster where askers < 5")).isZero();

        // The hook: CLUSTER_EMERGED reaches the rule engine through the qa outbox.
        awaitNotification("CLUSTER_EMERGED");
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'KNOWLEDGE_HOOK_FIRED' "
                + "and actor_sub = 'system:knowledge-hooks' and target_type = 'QUESTION_CLUSTER' "
                + "and detail_code = 'CLUSTER_EMERGED_NOTIFY'")).isEqualTo(1);
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'KNOWLEDGE_DIAGNOSTICS_RUN' "
                + "and actor_sub = ? and detail_code = 'MANUAL'", adminSub)).isEqualTo(1);

        // Three more askers: the same cluster grew (8 >= 5 x 1.25).
        for (int i = 6; i <= 8; i++) assertNotFound(ask(token("acct-health-vpn" + i + "-" + suffix), VPN));
        assertThat(send(admin, "POST", "/v1/admin/knowledge-health/runs", null).statusCode()).isEqualTo(201);
        HttpResponse<String> again = report(admin);
        assertThat((String) JsonPath.read(again.body(), "$.clusters[0].trend")).isEqualTo("GROWING");
        assertThat((Integer) JsonPath.read(again.body(), "$.clusters[0].previousTurns")).isEqualTo(5);
        assertThat((List<Object>) JsonPath.read(again.body(), "$.trend")).hasSize(2);
        awaitNotification("CLUSTER_GROWING");
        // Each question was embedded once: the second run reused the first run's vectors.
        assertThat(QaTables.count("select count(*) from qa.turn_question_vector")).isEqualTo(16);

        // The export for the consulting team: an attachment, the same filtering.
        HttpResponse<String> md = get(admin, "/v1/admin/knowledge-health/export?from=" + today().minusDays(89) + "&to="
                + today());
        assertThat(md.statusCode()).isEqualTo(200);
        assertThat(md.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/markdown");
        assertThat(md.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment")
                .contains("bilgi-sagligi-");
        assertThat(md.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(md.body()).startsWith("# Bilgi sağlığı raporu").contains("Cevapsız kalan soru kümesi")
                .doesNotContain("MarkerHealthQ").doesNotContain("eposta");
        HttpResponse<String> asJson = get(admin, "/v1/admin/knowledge-health/export?from=" + today() + "&to=" + today()
                + "&format=JSON");
        assertThat(asJson.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat((Integer) JsonPath.read(asJson.body(), "$.clusters[0].askers")).isEqualTo(8);
        assertThat(AuditTables.awaitCount(4, "select count(*) from audit.event where action = 'KNOWLEDGE_HEALTH_VIEWED' "
                + "and detail_code in ('VIEW', 'MARKDOWN', 'JSON')")).isEqualTo(4);
        assertIdsOnlyInAudit("MarkerHealth");
        assertNoContentLogged();
    }

    // ---------------------------------------------------------------- the run lock

    @Test
    void run_whileAnotherInstanceHoldsTheLock_is409_andAnExpiredLeaseIsTakenOver() throws Exception {
        QaTables.update("INSERT INTO qa.diagnostic_run (run_trigger, run_day, status, claim_token, locked_until, "
                + "window_from, window_to, min_askers) VALUES ('MANUAL', current_date, 'RUNNING', gen_random_uuid(), "
                + "now() + interval '1 hour', current_date - 29, current_date, 5)");
        assertError(send(admin, "POST", "/v1/admin/knowledge-health/runs", null), 409, 11061);
        assertThat(QaTables.count("select count(*) from qa.diagnostic_run")).isEqualTo(1);

        // The holder died: its lease ran out; the next claimer marks it FAILED and runs.
        QaTables.update("UPDATE qa.diagnostic_run SET locked_until = now() - interval '1 minute'");
        HttpResponse<String> run = send(admin, "POST", "/v1/admin/knowledge-health/runs", null);
        assertThat(run.statusCode()).as(run.body()).isEqualTo(201);
        assertThat(QaTables.count("select count(*) from qa.diagnostic_run where status = 'FAILED'")).isEqualTo(1);
        assertThat(QaTables.count("select count(*) from qa.diagnostic_run where status = 'SUCCEEDED'")).isEqualTo(1);
        HttpResponse<String> runs = get(admin, "/v1/admin/knowledge-health/runs");
        assertThat((Integer) JsonPath.read(runs.body(), "$.page.totalElements")).isEqualTo(2);
        // The instance that lost its lease can no longer store a result: its claim token is gone.
        UUID failed = failedRun();
        assertThat(diagnosticsRuns.finish(failed, UUID.randomUUID(), new KnowledgeDiagnosticsRepository.Totals(1, 0, 0, 0,
                null, "{}"), java.time.Instant.now())).isFalse();
    }

    @Test
    void run_whenTwoInstancesStartAtOnce_onlyOneHoldsTheLock() throws Exception {
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Boolean>> results = pool.invokeAll(List.of(() -> {
                start.await();
                return diagnostics.run("MANUAL").isPresent();
            }, () -> {
                start.await();
                return diagnostics.run("MANUAL").isPresent();
            }));
            long ran = 0;
            for (var result : results) if (result.get()) ran++;
            // Either the second claim met the RUNNING row (one run), or it came after the first finished (two runs);
            // never two RUNNING rows at once (partial unique index).
            assertThat(ran).isBetween(1L, 2L);
            assertThat(QaTables.count("select count(*) from qa.diagnostic_run where status = 'RUNNING'")).isZero();
            assertThat(QaTables.count("select count(*) from qa.diagnostic_run where status = 'SUCCEEDED'")).isEqualTo(ran);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- contradictions and access filtering

    @Test
    void contradictions_showValuesOnlyToAnAdminWhoReadsBothDocuments() throws Exception {
        UUID first = uploadInto(hukuk, "MarkerHealthDocA.pdf", "topic-leave Yillik izin suresi 14 is gunudur.");
        UUID second = uploadInto(ik, "MarkerHealthDocB.pdf", "topic-leave Yillik izin suresi 16 is gunudur.");
        process();
        assertThat(send(admin, "POST", "/v1/admin/knowledge-health/runs", null).statusCode()).isEqualTo(201);
        assertThat(QaTables.count("select count(*) from qa.contradiction_candidate")).isEqualTo(1);

        HttpResponse<String> reader = report(admin);
        assertThat((List<String>) JsonPath.read(reader.body(), "$.contradictions[0].values[*].first"))
                .containsAnyOf("14", "16");
        assertThat((Boolean) JsonPath.read(reader.body(), "$.contradictions[0].first.readable")).isTrue();
        assertThat(reader.body()).contains("MarkerHealthDocA.pdf").contains("MarkerHealthDocB.pdf");
        assertThat((List<String>) JsonPath.read(reader.body(), "$.findings[*].code")).contains("CONTRADICTIONS");

        // Reads neither: ids and pages only, no value, no name.
        HttpResponse<String> blind = report(blindAdmin);
        assertThat(blind.statusCode()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(blind.body(), "$.contradictions[0].first.readable")).isFalse();
        assertThat((Boolean) JsonPath.read(blind.body(), "$.contradictions[0].second.readable")).isFalse();
        assertThat((List<Object>) JsonPath.read(blind.body(), "$.contradictions[0].values")).isEmpty();
        assertThat(blind.body()).doesNotContain("MarkerHealthDoc").contains(first.toString()).contains(second.toString());

        // Reads one side only: that side is named, the values (which come from both texts) stay hidden.
        String oneSide = TestIdp.IDP.token().subject("acct-health-half-" + suffix).realmRoles("verso-admin")
                .claim("groups", List.of("/hukuk")).build();
        HttpResponse<String> half = report(oneSide);
        assertThat((List<Object>) JsonPath.read(half.body(), "$.contradictions[0].values")).isEmpty();
        assertThat(half.body()).contains("MarkerHealthDocA.pdf").doesNotContain("MarkerHealthDocB.pdf");

        // The hook flagged it for review; reading the flag clears it.
        String flag = awaitNotification("CONTRADICTION_FOUND");
        assertThat((Integer) JsonPath.read(report(admin).body(), "$.openFlags")).isEqualTo(1);
        HttpResponse<String> read = send(admin, "POST", "/v1/admin/notifications/" + flag + "/read", null);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(report(admin).body(), "$.openFlags")).isZero();
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'KNOWLEDGE_NOTIFICATION_READ' "
                + "and target_id = ?::uuid", flag)).isEqualTo(1);

        // A changed document drops its candidates.
        assertThat(send(admin, "DELETE", "/v1/documents/" + second, null).statusCode()).isEqualTo(204);
        awaitCount("select count(*) from qa.contradiction_candidate", 0);
        assertIdsOnlyInAudit("MarkerHealthDoc");
        assertNoContentLogged();
    }

    // ---------------------------------------------------------------- hooks

    @Test
    void hooks_fireOnADocumentChangeAndADispute_andAdminsSwitchRules() throws Exception {
        UUID document = uploadInto(hukuk, "izin.pdf", "topic-leave Yillik izin on dort gundur.");
        process();
        UUID card = verifiedCard(document);

        // A dispute: three distinct users rate the verified answer down.
        for (int i = 1; i <= 3; i++) {
            String rater = token("acct-health-r" + i + "-" + suffix, "/hukuk");
            rateDown(rater, ask(rater, LEAVE));
        }
        String disputed = awaitNotification("CARD_DISPUTED");
        HttpResponse<String> list = get(admin, "/v1/admin/notifications?unread=true");
        assertThat((List<String>) JsonPath.read(list.body(), "$.data[?(@.trigger == 'CARD_DISPUTED')].action"))
                .containsExactly("FLAG");
        assertThat((List<String>) JsonPath.read(list.body(), "$.data[?(@.trigger == 'CARD_DISPUTED')].subjectId"))
                .containsExactly(card.toString());
        assertThat(list.body()).doesNotContain("acct-health-r");
        assertThat(disputed).isNotBlank();

        // Switch off the stale notice: the document change still demotes the card (a guarantee), but no notice.
        HttpResponse<String> off = send(admin, "PUT", "/v1/admin/knowledge-hooks/CARD_STALE_NOTIFY", "{\"enabled\":false}");
        assertThat(off.statusCode()).as(off.body()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(off.body(), "$.enabled")).isFalse();
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'KNOWLEDGE_HOOK_RULE_CHANGED' "
                + "and detail_code = 'CARD_STALE_NOTIFY_OFF' and actor_sub = ?", adminSub)).isEqualTo(1);
        HttpResponse<String> rules = get(admin, "/v1/admin/knowledge-hooks");
        assertThat((List<Boolean>) JsonPath.read(rules.body(), "$[?(@.key == 'CONTRADICTION_DEMOTE_CARD')].enabled"))
                .containsExactly(false);
        assertThat((List<String>) JsonPath.read(rules.body(), "$[*].action"))
                .allMatch(a -> List.of("NOTIFY", "FLAG", "DEMOTE", "RUN_EVAL").contains(a));

        assertThat(send(admin, "DELETE", "/v1/documents/" + document, null).statusCode()).isEqualTo(204);
        awaitCount("select count(*) from qa.knowledge_card where state = 'STALE'", 1);
        // The document change asked for the golden-set check (a golden case cites it): one coalesced request.
        awaitCount("select count(*) from qa.hook_eval_request where rule_key = 'DOCUMENT_CHANGED_RUN_EVAL'", 1);
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'KNOWLEDGE_HOOK_FIRED' "
                + "and target_type = 'DOCUMENT' and target_id = ?::uuid and detail_code = 'DOCUMENT_CHANGED_RUN_EVAL'",
                document.toString())).isEqualTo(1);
        assertThat(QaTables.count("select count(*) from qa.hook_notification where trigger_type = 'CARD_STALE'")).isZero();

        // After the quiet period the scheduler runs the check once, as a HOOK run of the system.
        QaTables.update("UPDATE qa.hook_eval_request SET requested_at = now() - interval '1 hour'");
        hooks.runRequestedEval();
        assertThat(QaTables.count("select count(*) from qa.eval_run where run_trigger = 'HOOK'")).isEqualTo(1);
        assertThat(QaTables.count("select count(*) from qa.hook_eval_request")).isZero();
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'KNOWLEDGE_EVAL_RUN' "
                + "and detail_code = 'HOOK' and actor_sub = 'system:knowledge-memory'")).isEqualTo(1);
        assertIdsOnlyInAudit("MarkerHealthCard");
        assertNoContentLogged();
    }

    // ---------------------------------------------------------------- access

    @Test
    void everyEndpoint_isAdminOnly_andBindingIsChecked() throws Exception {
        String today = today().toString();
        List<String[]> endpoints = List.of(
                new String[] {"GET", "/v1/admin/knowledge-health?from=" + today + "&to=" + today},
                new String[] {"GET", "/v1/admin/knowledge-health/export?from=" + today + "&to=" + today},
                new String[] {"GET", "/v1/admin/knowledge-health/runs"},
                new String[] {"POST", "/v1/admin/knowledge-health/runs"},
                new String[] {"GET", "/v1/admin/knowledge-hooks"},
                new String[] {"PUT", "/v1/admin/knowledge-hooks/CARD_STALE_NOTIFY"},
                new String[] {"GET", "/v1/admin/notifications"},
                new String[] {"POST", "/v1/admin/notifications/" + UUID.randomUUID() + "/read"});
        for (String[] endpoint : endpoints) {
            String body = endpoint[0].equals("PUT") ? "{\"enabled\":false}" : null;
            assertError(send(user, endpoint[0], endpoint[1], body), 403, 90101);
            HttpRequest.Builder anonymous = HttpRequest.newBuilder(URI.create(base() + endpoint[1]))
                    .timeout(Duration.ofSeconds(30)).method(endpoint[0], HttpRequest.BodyPublishers.noBody());
            assertThat(http.send(anonymous.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                    .as(endpoint[0] + " " + endpoint[1]).isEqualTo(401);
        }
        assertThat(QaTables.count("select count(*) from qa.diagnostic_run")).isZero();
        assertThat(QaTables.count("select count(*) from qa.hook_rule where enabled <> default_enabled")).isZero();

        assertError(get(admin, "/v1/admin/knowledge-health?from=" + today + "&to=" + today().minusDays(1)), 400, 11060);
        assertError(get(admin, "/v1/admin/knowledge-health?from=" + today().minusDays(500) + "&to=" + today), 400, 11060);
        assertThat(get(admin, "/v1/admin/knowledge-health?from=nope&to=" + today).statusCode()).isEqualTo(400);
        assertThat(get(admin, "/v1/admin/knowledge-health/export?from=" + today + "&to=" + today + "&format=PDF")
                .statusCode()).isEqualTo(400);
        assertError(send(admin, "PUT", "/v1/admin/knowledge-hooks/NOPE_RULE", "{\"enabled\":true}"), 404, 11062);
        assertThat(send(admin, "PUT", "/v1/admin/knowledge-hooks/bad-key", "{\"enabled\":true}").statusCode())
                .isEqualTo(400);
        assertThat(send(admin, "PUT", "/v1/admin/knowledge-hooks/CARD_STALE_NOTIFY", "{}").statusCode()).isEqualTo(400);
        assertError(send(admin, "POST", "/v1/admin/notifications/" + UUID.randomUUID() + "/read", null), 404, 11063);

        // No run yet: an empty report that says so.
        HttpResponse<String> empty = report(admin);
        assertThat(empty.statusCode()).isEqualTo(200);
        assertThat((Object) JsonPath.read(empty.body(), "$.run")).isNull();
        assertThat((List<String>) JsonPath.read(empty.body(), "$.findings[*].code")).containsExactly("NO_RUN");
    }

    // ---------------------------------------------------------------- helpers

    private UUID verifiedCard(UUID document) throws Exception {
        TestChatModel.INSTANCE.answer("MarkerHealthCardA yıllık izin süresi bu belgede tanımlıdır [1].");
        HttpResponse<String> asked = ask(admin, LEAVE);
        assertThat((String) JsonPath.read(asked.body(), "$.outcome")).as(asked.body()).isEqualTo("ANSWERED");
        assertThat((List<String>) JsonPath.read(asked.body(), "$.citations[*].documentId"))
                .containsExactly(document.toString());
        String conversation = JsonPath.read(asked.body(), "$.conversationId");
        String turn = JsonPath.read(asked.body(), "$.turnId");
        HttpResponse<String> created = send(admin, "POST", "/v1/admin/knowledge-cards", "{\"conversationId\":\""
                + conversation + "\",\"turnId\":\"" + turn + "\",\"verify\":true}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.id"));
    }

    private void rateDown(String token, HttpResponse<String> answered) throws Exception {
        assertThat((Object) JsonPath.read(answered.body(), "$.memory")).as("served from the card").isNotNull();
        String conversation = JsonPath.read(answered.body(), "$.conversationId");
        String turn = JsonPath.read(answered.body(), "$.turnId");
        HttpResponse<String> rated = send(token, "POST", "/v1/conversations/" + conversation + "/turns/" + turn
                + "/feedback", "{\"rating\":\"DOWN\",\"reason\":\"OUTDATED\"}");
        assertThat(rated.statusCode()).as(rated.body()).isEqualTo(200);
    }

    /** Waits until a notification of the trigger exists (the outbox delivers after the commit); returns its id. */
    private String awaitNotification(String trigger) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            String body = get(admin, "/v1/admin/notifications?size=100").body();
            List<String> ids = JsonPath.read(body, "$.data[?(@.trigger == '" + trigger + "')].id");
            if (!ids.isEmpty()) {
                assertThat((List<String>) JsonPath.read(body, "$.data[*].title")).allMatch(t -> !t.contains("_"));
                return ids.getFirst();
            }
            assertThat(System.nanoTime()).as("a " + trigger + " notification appears: " + body).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private static UUID failedRun() throws Exception {
        try (var connection = QaTables.admin(); var st = connection.createStatement();
             var rs = st.executeQuery("select id from qa.diagnostic_run where status = 'FAILED'")) {
            assertThat(rs.next()).isTrue();
            return rs.getObject(1, UUID.class);
        }
    }

    private void awaitCount(String sql, long expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (QaTables.count(sql) != expected) {
            assertThat(System.nanoTime()).as(sql + " = " + expected).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    /** Audit rows of this layer hold ids and fixed words only: no question, no file name, no account of a rater. */
    private static void assertIdsOnlyInAudit(String marker) {
        assertThat(AuditTables.count("select count(*) from audit.event e where row_to_json(e)::text like ?",
                "%" + marker + "%")).isZero();
        assertThat(AuditTables.count("select count(*) from audit.event e where action like 'KNOWLEDGE_HOOK%' "
                + "and row_to_json(e)::text like '%acct-health-r%'")).isZero();
    }

    private void assertNoContentLogged() {
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.contains("MarkerHealth") || line.contains("vpn baglantisi")
                        || line.contains("izin suresi"));
    }

    private static void assertNotFound(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(response.body(), "$.outcome")).isEqualTo("NOT_FOUND");
    }

    private static void assertError(HttpResponse<String> response, int status, int code) {
        assertThat(response.statusCode()).as(response.request().method() + " " + response.request().uri() + " "
                + response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":" + code);
    }

    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Europe/Istanbul"));
    }

    private HttpResponse<String> report(String token) throws Exception {
        return get(token, "/v1/admin/knowledge-health?from=" + today().minusDays(30) + "&to=" + today());
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

    private UUID uploadInto(UUID collection, String fileName, String text) throws Exception {
        Multipart body = Multipart.pdf(fileName, TestPdfs.pages(text));
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

    private HttpResponse<String> ask(String token, String question) throws Exception {
        return send(token, "POST", "/v1/questions", "{\"question\":\"" + question + "\"}");
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
