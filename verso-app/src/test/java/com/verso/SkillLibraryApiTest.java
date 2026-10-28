package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.AuditTables;
import com.verso.support.Multipart;
import com.verso.support.QaTables;
import com.verso.support.TestChatModel;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import com.verso.support.WorkTables;
import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.enums.SkillCategory;
import com.verso.work.api.enums.SkillInputType;
import com.verso.work.api.enums.SkillSectionKind;
import com.verso.work.service.RunMaintenance;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The skill library end to end (ADR-0057) with the real database, outbox and retrieval and the stub chat model:
 *
 * <ul>
 *   <li>a run grounds only in what the caller may read, is stored as a DRAFT with its citations, and can be edited,
 *       approved, exported (Markdown, JSON), rated and deleted; the progress stream ends with {@code done};</li>
 *   <li>the one repair attempt; a busy model is a 503; a lost run is failed by the sweep;</li>
 *   <li>the admin switch hides a skill; customer-authored skills go DRAFT → APPROVED and cannot take a built-in id;</li>
 *   <li>ownership (another user's run is a 404) and the admin 403 matrix;</li>
 *   <li>audit rows of ids and fixed words; no form input and no output text in a log line.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class SkillLibraryApiTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String INPUT_MARKER = "MarkerSkillInput";
    private static final String ANSWER = "{\"sections\":{\"findings\":{\"rows\":["
            + "{\"finding\":\"Masraf limiti 900 TL.\",\"evidence\":[1]},"
            + "{\"finding\":\"Onay suresi\",\"evidence\":\"değerlendirilemez\"}]},"
            + "\"summary\":{\"text\":\"MarkerSkillOutput ozet.\",\"evidence\":[1]}}}";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestionWorker worker;

    @Autowired
    RunMaintenance maintenance;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String adminSub = "acct-lib-admin-" + suffix;
    private final String admin = TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    private final String aliceSub = "acct-lib-alice-" + suffix;
    /** Reads only the first collection. */
    private final String alice = token(aliceSub, "/hukuk");
    /** No group at all. */
    private final String carol = token("acct-lib-carol-" + suffix);
    private Logger root;
    private ListAppender<ILoggingEvent> appender;
    private UUID first;
    private UUID second;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection");
        QaTables.reset();
        AuditTables.reset();
        WorkTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
        TestChatModel.INSTANCE.reset();
        TestChatModel.INSTANCE.answer(ANSWER);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (worker.isPaused()) {
            assertThat(System.nanoTime()).as("ingestion worker stays paused").isLessThan(deadline);
            Thread.sleep(50);
        }
        UUID hukuk = collection("hukuk " + suffix);
        UUID ik = collection("ik " + suffix);
        grant(hukuk, "GROUP", "/hukuk");
        grant(ik, "GROUP", "/ik");
        first = uploadInto(hukuk, "masraf-hukuk.pdf", "topic-expense Hukuk: masraf limiti 900 TL dir.");
        second = uploadInto(ik, "masraf-ik.pdf", "topic-expense IK: masraf limiti 750 TL dir, MarkerHiddenIk.");
        process();
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

    // ---------------------------------------------------------------- a run, end to end

    @Test
    void run_whenFinished_isADraftGroundedOnlyInReadableDocumentsAndCanBeEditedApprovedExportedRated() throws Exception {
        UUID custom = approvedCustomSkill();
        HttpResponse<String> started = startRun(alice, "test-masraf");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        assertThat(started.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        UUID run = UUID.fromString(JsonPath.read(started.body(), "$.id"));
        String done = awaitFinished(alice, run);

        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(done, "$.disclaimer")).isEqualTo("Taslak - ekip incelemesi icin.");
        assertThat((List<String>) JsonPath.read(done, "$.citations[?(@.kind == 'PASSAGE')].documentId"))
                .containsOnly(first.toString()).doesNotContain(second.toString());
        assertThat((List<String>) JsonPath.read(done, "$.citations[?(@.kind == 'INPUT')].inputKey")).containsExactly("notlar");
        assertThat((Boolean) JsonPath.read(done, "$.output.sections[0].rows[1].notAssessable")).isTrue();
        assertThat((Integer) JsonPath.read(done, "$.checks.notAssessable")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(done, "$.checks.unverified")).isZero();
        assertThat((Boolean) JsonPath.read(done, "$.checks.repaired")).isFalse();
        String prompt = TestChatModel.INSTANCE.lastPrompt().getContents();
        // Only what the caller may read, and no file name leaves for the model (llm-rules 1.2).
        assertThat(prompt).contains("Hukuk: masraf limiti").doesNotContain("MarkerHiddenIk")
                .doesNotContain("masraf-hukuk.pdf");

        HttpResponse<String> events = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/skill-runs/" + run + "/events"))
                .header("Authorization", "Bearer " + alice).header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(events.statusCode()).isEqualTo(200);
        assertThat(events.body()).contains("event:done").contains("\"status\":\"DRAFT\"");

        String output = JSON.writeValueAsString(Map.of("output", editedOutput(done)));
        HttpResponse<String> edit = send(alice, "PUT", "/v1/skill-runs/" + run + "/output", output);
        assertThat(edit.statusCode()).as(edit.body()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(edit.body(), "$.edited")).isTrue();
        assertThat((List<String>) JsonPath.read(edit.body(), "$.output.sections[0].rows[0].unverified"))
                .containsExactly("950");

        HttpResponse<String> approved = send(alice, "POST", "/v1/skill-runs/" + run + "/approve", null);
        assertThat((String) JsonPath.read(approved.body(), "$.status")).isEqualTo("APPROVED");
        assertThat(send(alice, "POST", "/v1/skill-runs/" + run + "/rating", "{\"rating\":5}").statusCode()).isEqualTo(204);
        assertError(send(alice, "POST", "/v1/skill-runs/" + run + "/rating", "{\"rating\":9}"), 400, 90000);

        HttpResponse<String> md = get(alice, "/v1/skill-runs/" + run + "/export?format=MARKDOWN");
        assertThat(md.statusCode()).isEqualTo(200);
        assertThat(md.headers().firstValue("Content-Disposition").orElse("")).startsWith("attachment");
        assertThat(md.body()).contains("Taslak - ekip incelemesi icin.").contains("masraf-hukuk.pdf")
                .contains("_değerlendirilemez_").doesNotContain("MarkerHiddenIk");
        HttpResponse<String> js = get(alice, "/v1/skill-runs/" + run + "/export?format=JSON");
        assertThat((String) JsonPath.read(js.body(), "$.status")).isEqualTo("APPROVED");
        assertThat(js.body()).doesNotContain(INPUT_MARKER);
        assertError(get(alice, "/v1/skill-runs/" + run + "/export?format=HTML"), 400, 15014);

        HttpResponse<String> history = get(alice, "/v1/skill-runs?page=0&size=10");
        assertThat((Integer) JsonPath.read(history.body(), "$.total")).isEqualTo(1);
        assertThat(history.body()).doesNotContain(INPUT_MARKER).doesNotContain("MarkerSkillOutput");

        // Audit (ADR-0026): ids and fixed words only.
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'TASK_SKILL_RUN_STARTED' "
                + "and target_id = ? and detail_code = 'CUSTOM'", run)).isOne();
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'TASK_SKILL_RUN_FINISHED' "
                + "and target_id = ? and detail_code = 'DRAFT' and actor_sub = 'system:skill-runner' and ? = any(citation_document_ids)",
                run, first)).isOne();
        for (String action : List.of("TASK_SKILL_RUN_EDITED", "TASK_SKILL_RUN_APPROVED", "TASK_SKILL_RUN_RATED")) {
            assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = ? and target_id = ?",
                    action, run)).as(action).isOne();
        }
        assertThat(AuditTables.awaitCount(2, "select count(*) from audit.event where action = 'TASK_SKILL_RUN_EXPORTED'"))
                .isEqualTo(2);
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'TASK_SKILL_CUSTOM_APPROVED' "
                + "and target_id = ?", custom)).isOne();

        assertThat(send(alice, "DELETE", "/v1/skill-runs/" + run, null).statusCode()).isEqualTo(204);
        assertError(get(alice, "/v1/skill-runs/" + run), 404, 15004);
        assertThat(WorkTables.count("select count(*) from work.skill_run_source where run_id = ?", run)).isZero();
        assertNoContentLogged();
    }

    @Test
    void run_whenTheFirstOutputBreaksTheSchema_isRepairedOnce() throws Exception {
        approvedCustomSkill();
        AtomicInteger calls = new AtomicInteger();
        TestChatModel.INSTANCE.answer(prompt -> calls.getAndIncrement() == 0 ? "{\"sections\":{}}" : ANSWER);
        UUID run = UUID.fromString(JsonPath.read(startRun(alice, "test-masraf").body(), "$.id"));
        String done = awaitFinished(alice, run);
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("DRAFT");
        assertThat((Boolean) JsonPath.read(done, "$.checks.repaired")).isTrue();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void run_whenTheModelIsBusy_isA503AndNothingIsStored() throws Exception {
        approvedCustomSkill();
        CountDownLatch release = new CountDownLatch(1);
        TestChatModel.INSTANCE.answer(prompt -> {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ANSWER;
        });
        UUID run = UUID.fromString(JsonPath.read(startRun(alice, "test-masraf").body(), "$.id"));
        try {
            HttpResponse<String> busy = startRun(carol, "test-masraf");
            assertError(busy, 503, 15012);
            assertThat(busy.headers().firstValue("Retry-After")).isPresent();
            assertThat(WorkTables.count("select count(*) from work.skill_run")).isOne();
        } finally {
            release.countDown();
        }
        assertThat((String) JsonPath.read(awaitFinished(alice, run), "$.status")).isEqualTo("DRAFT");
    }

    @Test
    void sweep_whenARunsLeasePassed_failsItAsInterrupted() throws Exception {
        approvedCustomSkill();
        UUID run = UUID.fromString(JsonPath.read(startRun(alice, "test-masraf").body(), "$.id"));
        awaitFinished(alice, run);
        WorkTables.update("update work.skill_run set status = 'RUNNING', stage = 'GENERATING', step = 1, "
                + "lease_until = now() - interval '1 minute', output = null, checks = null, finished_at = null "
                + "where id = ?", run);
        assertThat(maintenance.failLostRuns()).isOne();
        HttpResponse<String> lost = get(alice, "/v1/skill-runs/" + run);
        assertThat((String) JsonPath.read(lost.body(), "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(lost.body(), "$.failureCode")).isEqualTo("INTERRUPTED");
    }

    /** One RUNNING run per user, decided by the partial unique index, not by a check-then-act. */
    @Test
    void start_whenTheCallerHasARunningRun_isA409AndReleasesTheModelSlot() throws Exception {
        approvedCustomSkill();
        WorkTables.update("insert into work.skill_run (owner_sub, skill_id, skill_version, definition, step_count, "
                + "inputs, lease_until) values (?, 'test-masraf', '1.0.0', '{}'::jsonb, 1, '{}'::jsonb, "
                + "now() + interval '5 minutes')", aliceSub);
        assertError(startRun(alice, "test-masraf"), 409, 15011);
        // The slot was given back: another user's run starts at once.
        HttpResponse<String> other = startRun(carol, "test-masraf");
        assertThat(other.statusCode()).as(other.body()).isEqualTo(202);
        awaitFinished(carol, UUID.fromString(JsonPath.read(other.body(), "$.id")));
    }

    // ---------------------------------------------------------------- forms, switches, custom skills

    @Test
    void start_whenTheFormIsInvalid_isA400NamingFieldsNotValues() throws Exception {
        HttpResponse<String> response = send(alice, "POST", "/v1/skills/premortem/runs",
                "{\"inputs\":{\"horizon\":\"" + INPUT_MARKER + "\",\"unknown\":\"x\"}}");
        assertError(response, 400, 15003);
        assertThat(response.body()).contains("charter: required").doesNotContain(INPUT_MARKER);
        assertError(send(alice, "POST", "/v1/skills/no-such-skill/runs", "{\"inputs\":{}}"), 404, 15001);
        // The body is bounded before Jackson reads it.
        assertError(send(alice, "POST", "/v1/skills/premortem/runs",
                "{\"inputs\":{\"charter\":\"" + "x".repeat(1_100_000) + "\"}}"), 413, 15015);
        assertThat(WorkTables.count("select count(*) from work.skill_run")).isZero();
        assertNoContentLogged();
    }

    @Test
    void adminSwitch_whenOff_hidesTheSkillAndRefusesItsRuns() throws Exception {
        assertThat(get(alice, "/v1/skills").body()).contains("\"id\":\"premortem\"");
        HttpResponse<String> off = send(admin, "PUT", "/v1/admin/task-skills/premortem/enabled", "{\"enabled\":false}");
        assertThat(off.statusCode()).as(off.body()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(off.body(), "$.enabled")).isFalse();
        assertThat(get(alice, "/v1/skills").body()).doesNotContain("\"id\":\"premortem\"");
        assertError(get(alice, "/v1/skills/premortem"), 404, 15002);
        assertError(send(alice, "POST", "/v1/skills/premortem/runs", "{\"inputs\":{\"charter\":\"x\",\"horizon\":\"3-ay\"}}"),
                404, 15002);
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'TASK_SKILL_TOGGLED' "
                + "and detail_code = 'DISABLED'")).isOne();
        assertThat(send(admin, "PUT", "/v1/admin/task-skills/premortem/enabled", "{\"enabled\":true}").statusCode())
                .isEqualTo(200);
        assertThat(get(alice, "/v1/skills").body()).contains("\"id\":\"premortem\"");
    }

    @Test
    void customSkill_whenSaved_isADraftUntilApprovedAndCannotTakeABuiltInId() throws Exception {
        HttpResponse<String> invalid = send(admin, "POST", "/v1/admin/custom-skills/validate",
                body(definition("test-masraf", "testmasraf", 9)));
        assertThat((Boolean) JsonPath.read(invalid.body(), "$.valid")).isFalse();
        assertThat(invalid.body()).contains("steps: 1..4 steps");
        assertError(send(admin, "POST", "/v1/admin/custom-skills", body(definition("premortem", "premortem2", 1))), 409, 15009);

        HttpResponse<String> created = send(admin, "POST", "/v1/admin/custom-skills", body(definition("test-masraf", "testmasraf", 1)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = JsonPath.read(created.body(), "$.id");
        assertThat((String) JsonPath.read(created.body(), "$.state")).isEqualTo("DRAFT");
        assertThat(get(alice, "/v1/skills").body()).doesNotContain("test-masraf");
        assertError(startRun(alice, "test-masraf"), 404, 15002);

        assertThat(send(admin, "POST", "/v1/admin/custom-skills/" + id + "/approve", null).statusCode()).isEqualTo(200);
        assertThat(get(alice, "/v1/skills").body()).contains("test-masraf");
        assertError(send(admin, "POST", "/v1/admin/custom-skills/" + id + "/approve", null), 409, 15010);

        HttpResponse<String> v2 = send(admin, "PUT", "/v1/admin/custom-skills/" + id, body(definition("test-masraf", "testmasraf", 1)));
        assertThat((Integer) JsonPath.read(v2.body(), "$.version")).isEqualTo(2);
        assertThat((String) JsonPath.read(v2.body(), "$.state")).isEqualTo("DRAFT");
        assertThat(get(alice, "/v1/skills").body()).doesNotContain("test-masraf");
        assertThat(send(admin, "POST", "/v1/admin/custom-skills/" + id + "/archive", null).statusCode()).isEqualTo(200);
        assertThat(WorkTables.count("select count(*) from work.custom_skill_version where custom_skill_id = ?::uuid", id))
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------- access

    @Test
    void runs_ofAnotherUser_areNotFound() throws Exception {
        approvedCustomSkill();
        UUID run = UUID.fromString(JsonPath.read(startRun(alice, "test-masraf").body(), "$.id"));
        awaitFinished(alice, run);
        for (String[] call : List.of(new String[]{"GET", "/v1/skill-runs/" + run},
                new String[]{"GET", "/v1/skill-runs/" + run + "/events"},
                new String[]{"POST", "/v1/skill-runs/" + run + "/approve"},
                new String[]{"GET", "/v1/skill-runs/" + run + "/export?format=JSON"},
                new String[]{"DELETE", "/v1/skill-runs/" + run})) {
            assertError(send(carol, call[0], call[1], null), 404, 15004);
            assertError(send(admin, call[0], call[1], null), 404, 15004);
        }
        assertThat((Integer) JsonPath.read(get(carol, "/v1/skill-runs").body(), "$.total")).isZero();
    }

    /** ADR-0019: the admin endpoints are verso-admin only (403 + ACCESS_DENIED), and nothing works without a token. */
    @Test
    void adminEndpoints_whenCalledByAUser_are403AndAudited() throws Exception {
        UUID any = UUID.randomUUID();
        List<String[]> endpoints = List.of(new String[]{"GET", "/v1/admin/task-skills"},
                new String[]{"PUT", "/v1/admin/task-skills/premortem/enabled"},
                new String[]{"GET", "/v1/admin/custom-skills"},
                new String[]{"GET", "/v1/admin/custom-skills/" + any},
                new String[]{"POST", "/v1/admin/custom-skills"},
                new String[]{"POST", "/v1/admin/custom-skills/validate"},
                new String[]{"PUT", "/v1/admin/custom-skills/" + any},
                new String[]{"POST", "/v1/admin/custom-skills/" + any + "/approve"},
                new String[]{"POST", "/v1/admin/custom-skills/" + any + "/archive"});
        for (String[] endpoint : endpoints) {
            String payload = endpoint[0].equals("GET") ? null : endpoint[1].endsWith("enabled") ? "{\"enabled\":false}"
                    : body(definition("x-skill", "xskill", 1));
            for (String user : List.of(alice, carol)) assertError(send(user, endpoint[0], endpoint[1], payload), 403, 90101);
            HttpRequest.Builder anonymous = HttpRequest.newBuilder(URI.create(base() + endpoint[1]))
                    .timeout(Duration.ofSeconds(30)).method(endpoint[0], HttpRequest.BodyPublishers.noBody());
            assertThat(http.send(anonymous.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        assertThat(AuditTables.awaitCount(18, "select count(*) from audit.event where action = 'ACCESS_DENIED' "
                + "and detail_code = 'ADMIN_TASK_SKILL'")).isEqualTo(18);
        for (String path : List.of("/v1/skills", "/v1/skill-runs")) {
            HttpRequest anonymous = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(30))
                    .GET().build();
            assertThat(http.send(anonymous, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        }
        assertThat(WorkTables.count("select count(*) from work.custom_skill")).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private UUID approvedCustomSkill() throws Exception {
        HttpResponse<String> created = send(admin, "POST", "/v1/admin/custom-skills", body(definition("test-masraf", "testmasraf", 1)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(created.body(), "$.id"));
        assertThat(send(admin, "POST", "/v1/admin/custom-skills/" + id + "/approve", null).statusCode()).isEqualTo(200);
        return id;
    }

    private HttpResponse<String> startRun(String token, String skillId) throws Exception {
        return send(token, "POST", "/v1/skills/" + skillId + "/runs",
                "{\"inputs\":{\"konu\":\"topic-expense masraf limiti\",\"notlar\":\"" + INPUT_MARKER + " toplantida konusuldu.\"}}");
    }

    private String awaitFinished(String token, UUID run) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            HttpResponse<String> response = get(token, "/v1/skill-runs/" + run);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            if (!"RUNNING".equals(JsonPath.read(response.body(), "$.status"))) return response.body();
            assertThat(System.nanoTime()).as("run finishes").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    /** The run's output with the first row's text changed to a number its source does not give. */
    private static Object editedOutput(String run) {
        Map<String, Object> output = JsonPath.read(run, "$.output");
        String text = JSON.writeValueAsString(output).replace("Masraf limiti 900 TL.", "Masraf limiti 950 TL.");
        return JSON.readValue(text, Object.class);
    }

    private static SkillDefinition definition(String id, String slash, int steps) {
        List<SkillDefinition.Step> chain = new java.util.ArrayList<>();
        for (int i = 1; i < steps; i++) {
            chain.add(new SkillDefinition.Step("not" + i, "Not " + i, "Kaynaklari oku ve kisa notlar yaz.", 500));
        }
        chain.add(new SkillDefinition.Step("yaz", "Bulgulari yaz", "Masraf kurallarini kaynaklara dayanarak bulgu olarak yaz.", null));
        return new SkillDefinition(1, id, "1.0.0", "Masraf kurali taramasi", "Masraf kurallarini kaynaklardan bulgu olarak cikarir.",
                SkillCategory.PROCESS, slash,
                List.of(new SkillDefinition.Input("konu", "Konu", SkillInputType.TEXT, true, 200, null, null, null, null),
                        new SkillDefinition.Input("notlar", "Notlar", SkillInputType.LONG_TEXT, false, 2000, null, null, null, null)),
                new SkillDefinition.Grounding(List.of("{{konu}}"), 4, null, null, null), chain,
                new SkillDefinition.Output(List.of(
                        new SkillDefinition.Section("findings", "Bulgular", SkillSectionKind.ROWS,
                                List.of(new SkillDefinition.Column("finding", "Bulgu", 300, null)), 0, 10, null),
                        new SkillDefinition.Section("summary", "Ozet", SkillSectionKind.TEXT, null, null, null, 400))),
                3000,
                List.of(new SkillDefinition.RubricDimension("groundedness", "Dayanak", "Her bulgu kaynaga dayanir."),
                        new SkillDefinition.RubricDimension("coverage", "Kapsam", "Butun kurallar listelenir."),
                        new SkillDefinition.RubricDimension("length", "Uzunluk", "Kisa ve oz yazilir.")),
                new SkillDefinition.Framework("Ic kontrol", "Sirket masraf politikasi"), "Taslak - ekip incelemesi icin.",
                false, null, null);
    }

    private static String body(SkillDefinition definition) {
        return JSON.writeValueAsString(Map.of("definition", definition));
    }

    private void assertNoContentLogged() {
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.contains(INPUT_MARKER) || line.contains("MarkerSkillOutput")
                        || line.contains("masraf limiti") || line.contains("MarkerHiddenIk"));
    }

    private static void assertError(HttpResponse<String> response, int status, int code) {
        assertThat(response.statusCode()).as(response.request().method() + " " + response.request().uri() + " "
                + response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":" + code);
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

    private HttpResponse<String> get(String token, String path) throws Exception {
        return send(token, "GET", path, null);
    }

    private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(30));
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
