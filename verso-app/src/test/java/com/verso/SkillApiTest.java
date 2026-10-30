package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.AuditTables;
import com.verso.support.Multipart;
import com.verso.support.QaTables;
import com.verso.support.TestChatModel;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Skills (procedures) end to end (ADR-0053) with the real database, the real outbox, the MCP server and stub models:
 *
 * <ul>
 *   <li>drafting from verified cards and from a diagnostics cluster, editing into a new version, approval rules (2-5
 *       golden cases, every step grounded), the version diff and the SKILL.md export;</li>
 *   <li>serving above the answer only to callers who read every source (the collection matrix), the stream's
 *       {@code done} event, nothing for a draft or another question;</li>
 *   <li>STALE on a source change (outbox) and on a lost hash (nightly check), the owners' notification;</li>
 *   <li>verso_skills and verso_skill as the caller with the same access matrix;</li>
 *   <li>every admin endpoint verso-admin only; audit rows of ids and fixed words; no step or question in a log line.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "VERSO_MCP_ENABLED=true", "verso.qa.skills.serving-enabled=true"})
@VersoTestEnvironment
class SkillApiTest {

    private static final String QUESTION = "topic-leave MarkerSkillQ yıllık izin kaç gündür?";
    /**
     * The asked quantity (ADR-0064: "kaç gün" needs a number of days), as both sources state it ("on dort gundur"): the
     * groundedness check verifies it, the step is grounded.
     */
    private static final String ANSWER = "MarkerSkillA yıllık izin on dört gündür [1][2].";
    private static final String STEP = "MarkerSkillA yıllık izin on dört gündür.";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestionWorker worker;

    @Autowired
    ModelCircuitBreaker circuit;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String adminSub = "acct-skill-admin-" + suffix;
    private final String admin = TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    private final String daveSub = "acct-skill-dave-" + suffix;
    private final String aliceSub = "acct-skill-alice-" + suffix;
    private final String carolSub = "acct-skill-carol-" + suffix;
    /** Reads both collections. */
    private final String dave = token(daveSub, "/hukuk", "/ik");
    /** Reads only the first collection. */
    private final String alice = token(aliceSub, "/hukuk");
    /** No group at all. */
    private final String carol = token(carolSub);
    private final List<McpSyncClient> clients = new ArrayList<>();
    private Logger root;
    private ListAppender<ILoggingEvent> appender;
    private UUID hukuk;
    private UUID ik;
    private UUID first;
    private UUID second;

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
        first = uploadInto(hukuk, "izin-hukuk.pdf", "topic-leave Hukuk: yillik izin on dort gundur.");
        second = uploadInto(ik, "izin-ik.pdf", "topic-leave IK: yillik izin on dort gundur, devreder.");
        process();
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        clients.forEach(McpSyncClient::closeGracefully);
        root.detachAppender(appender);
        appender.stop();
    }

    // ---------------------------------------------------------------- drafting, approval, serving

    @Test
    void approvedSkill_isShownAboveTheAnswer_onlyToCallersWhoReadEverySource() throws Exception {
        UUID skill = approvedSkill("izin-sureci");

        HttpResponse<String> served = ask(dave, QUESTION);
        assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(served.body(), "$.procedure.skillId")).isEqualTo(skill.toString());
        assertThat((String) JsonPath.read(served.body(), "$.procedure.name")).isEqualTo("izin-sureci");
        assertThat((String) JsonPath.read(served.body(), "$.procedure.steps[0].instruction")).isEqualTo(STEP);
        assertThat((List<String>) JsonPath.read(served.body(), "$.procedure.steps[0].sources[*].documentId"))
                .containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat((List<String>) JsonPath.read(served.body(), "$.procedure.steps[0].sources[*].passageId"))
                .doesNotContainNull();
        assertThat((String) JsonPath.read(served.body(), "$.answer")).as("the answer stands").isNotBlank();

        // One collection of two, or none: no procedure, exactly as if the skill did not exist.
        for (String token : List.of(alice, carol)) {
            HttpResponse<String> other = ask(token, QUESTION);
            assertThat(other.statusCode()).as(other.body()).isEqualTo(200);
            assertThat((Object) JsonPath.read(other.body(), "$.procedure")).isNull();
            assertThat(other.body()).doesNotContain("izin-sureci");
        }
        // Another question is below the threshold.
        TestChatModel.INSTANCE.answer("Normal yol [1].");
        assertThat((Object) JsonPath.read(ask(dave, "topic-holiday Resmi tatiller hangileri?").body(), "$.procedure"))
                .isNull();
        // The stream carries the same procedure in its done event.
        HttpResponse<String> stream = stream(dave, QUESTION);
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.body()).contains("\"procedure\":{\"skillId\":\"" + skill + "\"");

        // The SKILL.md bundle: spec frontmatter, the steps and verso_fetch links, never the document text.
        HttpResponse<byte[]> export = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/admin/skills/" + skill
                        + "/export")).header("Authorization", "Bearer " + admin).timeout(Duration.ofSeconds(30)).GET()
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.headers().firstValue("Content-Type")).contains("application/zip");
        assertThat(export.headers().firstValue("Content-Disposition").orElseThrow()).contains("izin-sureci.zip");
        assertThat(export.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        Map.Entry<String, String> entry = unzip(export.body());
        assertThat(entry.getKey()).isEqualTo("izin-sureci/SKILL.md");
        assertThat(entry.getValue()).startsWith("---\nname: izin-sureci\ndescription: \"")
                .contains("1. " + STEP + " [1](verso://passages/").contains("## Ne zaman kullanılır")
                .doesNotContain("on dort").doesNotContain("izin-hukuk.pdf");
        assertThat(entry.getValue().split("\n")).hasSizeLessThan(500);

        AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'SKILL_EXPORTED' and target_id = ?",
                skill);
        for (String[] row : List.of(new String[] {"SKILL_DRAFTED", "MANUAL"}, new String[] {"SKILL_EDITED", null},
                new String[] {"SKILL_APPROVED", "APPROVED"}, new String[] {"SKILL_EXPORTED", null})) {
            String sql = "select count(*) from audit.event where action = ? and target_id = ? and actor_sub = ?"
                    + (row[1] == null ? "" : " and detail_code = '" + row[1] + "'");
            assertThat(AuditTables.awaitCount(1, sql, row[0], skill, adminSub)).as(row[0]).isEqualTo(1);
        }
        assertNoContentLogged();
    }

    @Test
    void drafting_fromACluster_suggestions_editing_diff_andTheApprovalRules() throws Exception {
        UUID card = verifiedCard();
        // A diagnostics run with one cluster around the card's question (the nightly job's output, ADR-0052).
        UUID run = UUID.randomUUID();
        UUID cluster = UUID.randomUUID();
        QaTables.update("insert into qa.diagnostic_run (id, run_trigger, run_day, status, window_from, window_to, "
                + "min_askers, finished_at) values (?, 'MANUAL', current_date, 'SUCCEEDED', current_date - 30, "
                + "current_date, 5, now())", run);
        QaTables.update("insert into qa.question_cluster (id, run_id, position, centroid, embedding_model, turns, askers, "
                + "trend, keywords) select ?, ?, 1, question_embedding, embedding_model, 7, 5, 'NEW', "
                + "ARRAY['izin', 'yıllık'] from qa.knowledge_card where id = ?", cluster, run, card);

        HttpResponse<String> suggestions = get(admin, "/v1/admin/skills/suggestions");
        assertThat(suggestions.statusCode()).as(suggestions.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(suggestions.body(), "$[0].clusterId")).isEqualTo(cluster.toString());
        assertThat((Integer) JsonPath.read(suggestions.body(), "$[0].verifiedCards")).isEqualTo(1);
        assertThat(suggestions.body()).doesNotContain("MarkerSkill");

        HttpResponse<String> drafted = send(admin, "POST", "/v1/admin/skills", "{\"clusterId\":\"" + cluster + "\"}");
        assertThat(drafted.statusCode()).as(drafted.body()).isEqualTo(201);
        assertThat((String) JsonPath.read(drafted.body(), "$.origin")).isEqualTo("CLUSTER");
        assertThat((String) JsonPath.read(drafted.body(), "$.name")).isEqualTo("izin-yillik");
        assertThat((String) JsonPath.read(drafted.body(), "$.state")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(drafted.body(), "$.steps[0].instruction")).isEqualTo(STEP);
        assertThat((Boolean) JsonPath.read(drafted.body(), "$.steps[0].grounded")).isTrue();
        assertThat((List<String>) JsonPath.read(drafted.body(), "$.triggers")).containsExactly(QUESTION);
        UUID skill = UUID.fromString(JsonPath.read(drafted.body(), "$.id"));
        assertThat((Object) JsonPath.read(ask(dave, QUESTION).body(), "$.procedure")).as("a draft is never served")
                .isNull();

        // One golden case only: approval refuses (ADR-0048: every skill ships with 2-5).
        assertError(send(admin, "POST", "/v1/admin/skills/" + skill + "/approve", null), 400, 11074);
        // A step stating a number its sources do not give is kept, flagged and blocks the approval (ADR-0047).
        HttpResponse<String> ungrounded = send(admin, "PUT", "/v1/admin/skills/" + skill,
                update("izin-yillik", "MarkerSkillA yıllık izin 99 gündür.", true));
        assertThat(ungrounded.statusCode()).as(ungrounded.body()).isEqualTo(200);
        assertThat((Boolean) JsonPath.read(ungrounded.body(), "$.steps[0].grounded")).isFalse();
        assertThat((Integer) JsonPath.read(ungrounded.body(), "$.version")).isEqualTo(2);
        assertError(send(admin, "POST", "/v1/admin/skills/" + skill + "/approve", null), 409, 11075);
        assertThat(send(admin, "PUT", "/v1/admin/skills/" + skill, update("izin-yillik", STEP, true)).statusCode())
                .isEqualTo(200);
        HttpResponse<String> approved = send(admin, "POST", "/v1/admin/skills/" + skill + "/approve", null);
        assertThat((String) JsonPath.read(approved.body(), "$.state")).as(approved.body()).isEqualTo("APPROVED");
        assertError(send(admin, "POST", "/v1/admin/skills/" + skill + "/approve", null), 409, 11071);

        HttpResponse<String> diff = get(admin, "/v1/admin/skills/" + skill + "/diff?from=1&to=2");
        assertThat((List<String>) JsonPath.read(diff.body(), "$.changes[*].field")).contains("step", "goldenCase");
        assertError(get(admin, "/v1/admin/skills/" + skill + "/diff?from=2&to=9"), 400, 11074);
        assertThat((List<Integer>) JsonPath.read(get(admin, "/v1/admin/skills/" + skill).body(), "$.versions[*].version"))
                .containsExactly(1, 2, 3);

        // ADR-0028: an admin without a grant on every source manages the skill but never sees its text.
        String outsider = TestIdp.IDP.token().subject("acct-skill-admin3-" + suffix).realmRoles("verso-admin").build();
        HttpResponse<String> hidden = get(outsider, "/v1/admin/skills/" + skill);
        assertThat((Boolean) JsonPath.read(hidden.body(), "$.readable")).isFalse();
        assertThat(hidden.body()).doesNotContain("MarkerSkill");
        assertThat(get(outsider, "/v1/admin/skills").body()).contains("\"readable\":false").doesNotContain("MarkerSkill");
        assertError(send(outsider, "POST", "/v1/admin/skills", "{\"cardIds\":[\"" + card + "\"]}"), 409, 11072);
        assertError(send(outsider, "GET", "/v1/admin/skills/" + skill + "/export", null), 409, 11073);

        // Drafting rules: a source is required, an unknown cluster or card is refused, a live name is unique.
        assertError(send(admin, "POST", "/v1/admin/skills", "{}"), 400, 11074);
        assertError(send(admin, "POST", "/v1/admin/skills", "{\"clusterId\":\"" + UUID.randomUUID() + "\"}"), 404, 11078);
        assertError(send(admin, "POST", "/v1/admin/skills", "{\"cardIds\":[\"" + UUID.randomUUID() + "\"]}"), 409, 11072);
        assertError(send(admin, "POST", "/v1/admin/skills", "{\"cardIds\":[\"" + card + "\"],\"name\":\"izin-yillik\"}"),
                409, 11077);
        assertThat(QaTables.count("select count(*) from qa.skill_version where skill_id = ?", skill)).isEqualTo(3);
        assertNoContentLogged();
    }

    // ---------------------------------------------------------------- staleness

    @Test
    void deletingASource_makesTheSkillStale_notifiesTheOwners_andStopsServingAndExport() throws Exception {
        UUID skill = approvedSkill("izin-sureci");
        assertThat((Object) JsonPath.read(ask(dave, QUESTION).body(), "$.procedure.skillId")).isEqualTo(skill.toString());

        assertThat(send(admin, "DELETE", "/v1/documents/" + second, null).statusCode()).isEqualTo(204);

        awaitState(skill, "STALE");
        HttpResponse<String> detail = get(admin, "/v1/admin/skills/" + skill);
        assertThat((String) JsonPath.read(detail.body(), "$.staleReason")).isEqualTo("SOURCE_DELETED");
        assertThat((List<String>) JsonPath.read(detail.body(), "$.events[*].reason")).contains("SOURCE_DELETED");
        assertThat((Object) JsonPath.read(ask(dave, QUESTION).body(), "$.procedure")).isNull();
        assertError(send(admin, "GET", "/v1/admin/skills/" + skill + "/export", null), 409, 11079);
        assertError(send(admin, "POST", "/v1/admin/skills/" + skill + "/approve", null), 409, 11071);
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'SKILL_STALE' and "
                + "actor_sub = 'system:knowledge-skills' and detail_code = 'SOURCE_DELETED' and target_id = ?", skill))
                .isEqualTo(1);
        // ADR-0052: the owners hear of it (the admins' notification inbox), ids and a fixed word only.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (QaTables.count("select count(*) from qa.hook_notification where rule_key = 'SKILL_STALE_NOTIFY' "
                + "and subject_type = 'SKILL' and subject_id = ?", skill) == 0) {
            assertThat(System.nanoTime()).as("SKILL_STALE_NOTIFY").isLessThan(deadline);
            Thread.sleep(100);
        }
        assertThat(get(admin, "/v1/admin/notifications").body()).contains("Bir prosedür eskidi");
        assertNoContentLogged();
    }

    @Test
    void nightlyCheck_runsTheSkillGoldenCases_andALostHashMakesTheSkillStale() throws Exception {
        UUID skill = approvedSkill("izin-sureci");
        int chatCalls = TestChatModel.INSTANCE.calls();
        HttpResponse<String> run = send(admin, "POST", "/v1/admin/knowledge-eval/runs", null);
        assertThat(run.statusCode()).as(run.body()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(run.body(), "$.skillCases")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(run.body(), "$.skillSourceLost")).isZero();
        assertThat(TestChatModel.INSTANCE.calls()).as("no chat call").isEqualTo(chatCalls);

        // The text goes away without the outbox noticing: the nightly check is the safety net.
        jdbc.update("DELETE FROM document.document_chunk WHERE document_id = ?", first);
        HttpResponse<String> again = send(admin, "POST", "/v1/admin/knowledge-eval/runs", null);
        assertThat((Integer) JsonPath.read(again.body(), "$.skillSourceLost")).isEqualTo(2);
        assertThat((String) JsonPath.read(get(admin, "/v1/admin/skills/" + skill).body(), "$.staleReason"))
                .isEqualTo("SOURCE_CHANGED");
        assertThat(QaTables.count("select count(*) from qa.skill_eval_result where skill_id = ?", skill)).isEqualTo(4);
    }

    // ---------------------------------------------------------------- MCP

    @Test
    void mcpTools_listAndReadApprovedSkills_asTheCaller_withTheSameAccessMatrix() throws Exception {
        UUID skill = approvedSkill("izin-sureci");
        // Through the API: the default JdbcTemplate is the document role, which cannot read the qa schema (by design).
        UUID card = UUID.fromString(JsonPath.read(get(admin, "/v1/admin/knowledge-cards?state=VERIFIED").body(),
                "$.data[0].id"));
        HttpResponse<String> draft = send(admin, "POST", "/v1/admin/skills", "{\"cardIds\":[\"" + card
                + "\"],\"name\":\"taslak-surec\"}");
        assertThat(draft.statusCode()).as(draft.body()).isEqualTo(201);

        McpSyncClient daves = client(mcpToken(daveSub, "/hukuk", "/ik"));
        List<String> tools = daves.listTools().tools().stream().map(t -> t.name()).toList();
        assertThat(tools).contains("verso_skills", "verso_skill");
        CallToolResult listed = daves.callTool(new CallToolRequest("verso_skills", Map.of()));
        assertThat(listed.isError()).isFalse();
        assertThat(names(listed)).as("approved only, a draft is not listed").containsExactly("izin-sureci");
        CallToolResult read = daves.callTool(new CallToolRequest("verso_skill", Map.of("name", "izin-sureci")));
        assertThat(read.isError()).as(String.valueOf(read.content())).isFalse();
        String markdown = (String) ((Map<String, Object>) read.structuredContent()).get("skill_md");
        assertThat(markdown).startsWith("---\nname: izin-sureci\n").contains(STEP).doesNotContain("on dort");
        String passage = markdown.substring(markdown.indexOf("verso://passages/") + 17).substring(0, 36);
        CallToolResult fetched = daves.callTool(new CallToolRequest("verso_fetch", Map.of("id", passage)));
        assertThat(fetched.isError()).as("a reference is a verso_fetch id the caller may read").isFalse();

        // One collection of two, or none: nothing listed, and reading it looks like an unknown name.
        String unknown = text(daves.callTool(new CallToolRequest("verso_skill", Map.of("name", "yok-boyle-bir-surec"))));
        for (McpSyncClient other : List.of(client(mcpToken(aliceSub, "/hukuk")), client(mcpToken(carolSub)))) {
            assertThat(names(other.callTool(new CallToolRequest("verso_skills", Map.of())))).isEmpty();
            CallToolResult refused = other.callTool(new CallToolRequest("verso_skill", Map.of("name", "izin-sureci")));
            assertThat(refused.isError()).isTrue();
            assertThat(text(refused)).isEqualTo(unknown);
        }
        assertThat(daves.callTool(new CallToolRequest("verso_skill", Map.of("name", "taslak-surec"))).isError())
                .as("a draft is not served").isTrue();
        assertThat(daves.callTool(new CallToolRequest("verso_skill", Map.of("name", "../Bad Name"))).isError()).isTrue();

        // Audit: ids and fixed words only.
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'MCP_SKILL' and "
                + "detail_code = 'FOUND' and target_id = ? and actor_sub = ?", skill, daveSub)).isEqualTo(1);
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'MCP_SKILLS' and "
                + "detail_code = 'RESULTS' and actor_sub = ?", daveSub)).isEqualTo(1);
        assertThat(AuditTables.awaitCount(2, "select count(*) from audit.event where action = 'MCP_SKILLS' and "
                + "detail_code = 'NO_RESULTS'")).isEqualTo(2);
        assertThat(AuditTables.count("select count(*) from audit.event where action = 'MCP_SKILL' and "
                + "detail_code = 'NOT_FOUND' and target_id is not null")).isZero();
        assertNoContentLogged();
    }

    // ---------------------------------------------------------------- admin only

    /** ADR-0019: every endpoint is verso-admin only; a user gets 403 ACCESS_DENIED, no token 401. */
    @Test
    void everyEndpoint_isAdminOnly() throws Exception {
        UUID any = UUID.randomUUID();
        List<String[]> endpoints = List.of(new String[] {"GET", "/v1/admin/skills"},
                new String[] {"GET", "/v1/admin/skills/suggestions"},
                new String[] {"POST", "/v1/admin/skills"},
                new String[] {"GET", "/v1/admin/skills/" + any},
                new String[] {"PUT", "/v1/admin/skills/" + any},
                new String[] {"POST", "/v1/admin/skills/" + any + "/approve"},
                new String[] {"POST", "/v1/admin/skills/" + any + "/archive"},
                new String[] {"GET", "/v1/admin/skills/" + any + "/diff?from=1&to=2"},
                new String[] {"GET", "/v1/admin/skills/" + any + "/export"});
        for (String[] endpoint : endpoints) {
            String payload = endpoint[0].equals("POST") && endpoint[1].endsWith("skills") ? "{\"cardIds\":[\"" + any
                    + "\"]}" : endpoint[0].equals("PUT") ? update("x", "y", false) : null;
            for (String user : List.of(dave, alice, carol)) {
                assertError(send(user, endpoint[0], endpoint[1], payload), 403, 90101);
            }
            HttpRequest.Builder anonymous = HttpRequest.newBuilder(URI.create(base() + endpoint[1]))
                    .timeout(Duration.ofSeconds(30)).method(endpoint[0], HttpRequest.BodyPublishers.noBody());
            assertThat(http.send(anonymous.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                    .as(endpoint[0] + " " + endpoint[1]).isEqualTo(401);
        }
        assertThat(QaTables.count("select count(*) from qa.skill")).isZero();
        // Binding: an unknown state, a bad name and a bidi override are 400 before anything is read.
        assertThat(get(admin, "/v1/admin/skills?state=NOPE").statusCode()).isEqualTo(400);
        assertThat(send(admin, "POST", "/v1/admin/skills", "{\"cardIds\":[\"" + any + "\"],\"name\":\"Bad Name\"}")
                .statusCode()).isEqualTo(400);
        assertThat(send(admin, "PUT", "/v1/admin/skills/" + any, update("x", "a\\u202Eb", false)).statusCode())
                .isEqualTo(400);
        assertError(get(admin, "/v1/admin/skills/" + any), 404, 11070);
        assertError(get(admin, "/v1/admin/skills/not-a-uuid"), 400, 90002);
    }

    // ---------------------------------------------------------------- helpers

    /** A verified card citing both documents, a skill drafted from it, edited to two golden cases and approved. */
    private UUID approvedSkill(String name) throws Exception {
        UUID card = verifiedCard();
        HttpResponse<String> drafted = send(admin, "POST", "/v1/admin/skills", "{\"cardIds\":[\"" + card
                + "\"],\"name\":\"" + name + "\"}");
        assertThat(drafted.statusCode()).as(drafted.body()).isEqualTo(201);
        UUID skill = UUID.fromString(JsonPath.read(drafted.body(), "$.id"));
        HttpResponse<String> edited = send(admin, "PUT", "/v1/admin/skills/" + skill, update(name, STEP, true));
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        HttpResponse<String> approved = send(admin, "POST", "/v1/admin/skills/" + skill + "/approve", null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(approved.body(), "$.state")).isEqualTo("APPROVED");
        return skill;
    }

    private UUID verifiedCard() throws Exception {
        TestChatModel.INSTANCE.answer(ANSWER);
        HttpResponse<String> asked = ask(admin, QUESTION);
        assertThat((String) JsonPath.read(asked.body(), "$.outcome")).as(asked.body()).isEqualTo("ANSWERED");
        String conversation = JsonPath.read(asked.body(), "$.conversationId");
        String turn = JsonPath.read(asked.body(), "$.turnId");
        HttpResponse<String> created = send(admin, "POST", "/v1/admin/knowledge-cards", "{\"conversationId\":\""
                + conversation + "\",\"turnId\":\"" + turn + "\",\"verify\":true}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.id"));
    }

    /** One step from step 1, the question as trigger, and two golden cases (one per source) when {@code golden}. */
    private String update(String name, String instruction, boolean golden) {
        String cases = golden ? "{\"question\":\"" + QUESTION + "\",\"documentId\":\"" + first + "\",\"page\":1},"
                + "{\"question\":\"topic-leave İK izni devreder mi?\",\"documentId\":\"" + second + "\",\"page\":1}" : "";
        return "{\"name\":\"" + name + "\",\"description\":\"Yıllık izin süresi prosedürü.\",\"steps\":[{\"from\":1,"
                + "\"instruction\":\"" + instruction + "\"}],\"triggers\":[\"" + QUESTION + "\"],\"goldenCases\":["
                + cases + "]}";
    }

    private void awaitState(UUID skill, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            String body = get(admin, "/v1/admin/skills/" + skill).body();
            if (state.equals(JsonPath.read(body, "$.state"))) return;
            assertThat(System.nanoTime()).as("skill " + skill + " becomes " + state + ": " + body).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    /** Neither a question nor a step reaches a log line (llm-rules 2.1). */
    private void assertNoContentLogged() {
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.contains("MarkerSkill") || line.contains("izin-sureci"));
    }

    private static Map.Entry<String, String> unzip(byte[] zip) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry = in.getNextEntry();
            assertThat(entry).isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(in.getNextEntry()).as("one file").isNull();
            return Map.entry(entry.getName(), text);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(CallToolResult result) {
        assertThat(result.isError()).as(String.valueOf(result.content())).isFalse();
        List<Map<String, Object>> skills = (List<Map<String, Object>>) ((Map<String, Object>) result.structuredContent())
                .get("skills");
        return skills.stream().map(s -> (String) s.get("name")).toList();
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private McpSyncClient client(String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base())
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        clients.add(client);
        client.initialize();
        return client;
    }

    private static String mcpToken(String subject, String... groups) {
        var builder = TestIdp.IDP.token().subject(subject).audience("verso-mcp");
        if (groups.length > 0) builder.claim("groups", List.of(groups));
        return builder.build();
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

    private HttpResponse<String> ask(String token, String question) throws Exception {
        return send(token, "POST", "/v1/questions", "{\"question\":\"" + question + "\"}");
    }

    private HttpResponse<String> stream(String token, String question) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/questions/stream"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question + "\"}"))
                .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
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
