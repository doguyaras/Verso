package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.AuditTables;
import com.verso.support.FakeModelServer;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The model gateway end to end in cloud mode (ADR-0056), against stub provider servers on 127.0.0.1: an
 * OpenAI-compatible one and an Anthropic one. Save validates and stores the key write-only and encrypted; the answer
 * route falls back on 5xx and 429; rotation re-validates; a hard budget blocks with 429; audit rows carry ids and fixed
 * words only; the admin endpoints are verso-admin only; and no key or question text reaches a log line.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "VERSO_AI_MODE=cloud", "VERSO_CHAT_PROVIDER=anthropic",
        "CLOUD_CHAT_MODEL=claude-test", "spring.ai.anthropic.api-key=test-anthropic-key"})
@VersoTestEnvironment
// No other class shares this configuration (cloud mode with the test chat model as the built-in one): its context is
// closed after the class, so its pools do not hold connections of the shared test database while others run.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ModelGatewayApiTest {

    /** Synthetic keys: never real, and distinctive so a leak is found by a plain search. */
    private static final String KEY_A = "sk-" + "compat".repeat(3) + "-AAAA1111";
    private static final String KEY_A2 = "sk-" + "rotated".repeat(3) + "-BBBB2222";
    private static final String KEY_B = "sk-ant-" + "anthropic".repeat(2) + "-CCCC3333";
    private static final String QUESTION = "topic-gateway Yillik izin kac gun?";

    static final FakeModelServer COMPAT = new FakeModelServer();
    static final FakeModelServer ANTHROPIC = new FakeModelServer();

    @AfterAll
    static void stop() {
        COMPAT.close();
        ANTHROPIC.close();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ModelCircuitBreaker circuit;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String adminSub = "acct-gw-admin-" + UUID.randomUUID();
    private final String admin = "Bearer " + TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    private final String account = "acct-gw-user-" + UUID.randomUUID();
    private Logger root;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() throws InterruptedException {
        QaTables.resetGateway();
        COMPAT.reset();
        ANTHROPIC.reset();
        TestChatModel.INSTANCE.reset();
        TestEmbeddingModel.INSTANCE.reset();
        circuit.reset();
        jdbc.update("DELETE FROM document.document");
        while (worker.isPaused()) Thread.sleep(50);
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        logs.stop();
        // A cloud connection left behind would stop a later local-mode context from starting (llm-rules 1.1).
        QaTables.resetGateway();
    }

    @Test
    void create_validatesTheKey_storesItEncryptedAndWriteOnly_andAuditsIdsOnly() throws Exception {
        COMPAT.answer(200, completion("OK"));

        HttpResponse<String> created = send("POST", "/v1/admin/model-gateway/connections", admin, compat(KEY_A));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).doesNotContain(KEY_A).doesNotContain("apiKey")
                .contains("\"keyStored\":true", "\"keyHint\":\"…1111\"", "\"validationStatus\":\"OK\"",
                        "\"locality\":\"LOCAL\"", "\"egressHost\":\"127.0.0.1\"");
        String id = JsonPath.read(created.body(), "$.id");
        // The validation call carried the key to the provider, and only there.
        assertThat(COMPAT.requests()).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/v1/chat/completions");
            assertThat(r.header("Authorization")).isEqualTo("Bearer " + KEY_A);
        });
        // At rest: ciphertext, wrapped data key and KEK id; the plaintext bytes are nowhere in the row.
        assertThat(QaTables.count("SELECT count(*) FROM qa.model_connection WHERE id = ?::uuid AND key_ciphertext IS NOT "
                + "NULL AND key_wrapped_dek IS NOT NULL AND key_kek_id LIKE 'local-%'", id)).isOne();
        assertThat(QaTables.count("SELECT count(*) FROM qa.model_connection WHERE position(convert_to(?, 'UTF8') IN "
                + "key_ciphertext || key_wrapped_dek) > 0 OR row_to_json(model_connection)::text LIKE ?", KEY_A,
                "%" + KEY_A + "%")).isZero();
        // The list never carries it either.
        assertThat(send("GET", "/v1/admin/model-gateway/connections", admin, null).body()).doesNotContain(KEY_A);
        // Audit: created and tested, by id, with the type and the result word only.
        AuditTables.awaitCount(2, "SELECT count(*) FROM audit.event WHERE target_id = ?::uuid", id);
        List<Map<String, Object>> events = AuditTables.rowsOf("SELECT * FROM audit.event WHERE target_id = ?::uuid", id);
        assertThat(events).extracting(e -> e.get("action")).containsExactlyInAnyOrder("MODEL_CONNECTION_CREATED",
                "MODEL_CONNECTION_TESTED");
        assertThat(events.toString()).doesNotContain(KEY_A).doesNotContain("127.0.0.1");
        assertNoSecretInLogs();
    }

    @Test
    void create_whenTheProviderRefusesTheKey_is422_andStoresNothing() throws Exception {
        COMPAT.answer(401, "{\"error\":{\"message\":\"Incorrect API key provided: " + KEY_A + "\"}}");

        HttpResponse<String> refused = send("POST", "/v1/admin/model-gateway/connections", admin, compat(KEY_A));

        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("\"code\":11095").doesNotContain(KEY_A).doesNotContain("Incorrect");
        assertThat(QaTables.count("SELECT count(*) FROM qa.model_connection")).isZero();
        assertNoSecretInLogs();
    }

    @Test
    void answerRoute_fallsBackOn5xxAnd429_andCountsTokensPerConnection() throws Exception {
        String a = createCompat();
        String b = createAnthropic();
        route("ANSWER", a, "compat-model", b, "claude-x");
        ready("izin.pdf", "topic-gateway Annual leave is twenty working days.");

        for (int status : new int[]{503, 429}) {
            COMPAT.reset();
            ANTHROPIC.reset();
            COMPAT.answer(status, "{\"error\":{\"message\":\"synthetic outage\"}}");
            ANTHROPIC.answer(200, message("Yillik izin yirmi is gunudur [1]."));

            HttpResponse<String> answer = ask(QUESTION, null);

            assertThat(answer.statusCode()).as("status %s", status).isEqualTo(200);
            assertThat(answer.body()).contains("yirmi");
            assertThat(COMPAT.requests()).hasSize(1);
            assertThat(ANTHROPIC.requests()).singleElement().satisfies(r -> {
                assertThat(r.header("x-api-key")).isEqualTo(KEY_B);
                assertThat(r.body()).doesNotContain("izin.pdf");
            });
        }
        assertThat(TestChatModel.INSTANCE.calls()).as("the built-in model was not asked").isZero();
        // Usage: per connection and task, never an account; the external call logged what kinds of data left.
        assertThat(QaTables.count("SELECT count(*) FROM qa.model_usage WHERE task = 'ANSWER' AND outcome = 'FAILED' "
                + "AND connection_id = ?::uuid", a)).isEqualTo(2);
        assertThat(QaTables.count("SELECT count(*) FROM qa.model_usage WHERE task = 'ANSWER' AND outcome = 'OK' "
                + "AND connection_id = ?::uuid AND prompt_tokens = 10 AND completion_tokens = 5 AND external "
                + "AND sent_categories = ARRAY['SYSTEM_INSTRUCTIONS','QUESTION','PASSAGES'] "
                + "AND filter_policy = 'PASS_THROUGH'", b)).isEqualTo(2);
        HttpResponse<String> usage = send("GET", "/v1/admin/model-gateway/usage", admin, null);
        assertThat(usage.statusCode()).isEqualTo(200);
        assertThat(usage.body()).doesNotContain(account).contains("\"minTeamUsers\":5");
        assertThat(JsonPath.<Integer>read(usage.body(), "$.requests")).isEqualTo(4);
        assertNoSecretInLogs();
    }

    @Test
    void rotation_validatesTheNewKey_bumpsTheVersion_andUsesItFromNowOn() throws Exception {
        String a = createCompat();
        route("ANSWER", a, "compat-model");
        COMPAT.reset();
        COMPAT.answer(200, completion("OK"));

        HttpResponse<String> rotated = send("POST", "/v1/admin/model-gateway/connections/" + a + "/key", admin,
                "{\"apiKey\":\"" + KEY_A2 + "\"}");

        assertThat(rotated.statusCode()).isEqualTo(200);
        assertThat(rotated.body()).doesNotContain(KEY_A2).contains("\"keyVersion\":2", "\"keyHint\":\"…2222\"");
        assertThat(COMPAT.requests().getLast().header("Authorization")).isEqualTo("Bearer " + KEY_A2);
        ready("izin.pdf", "topic-gateway Annual leave is twenty working days.");
        COMPAT.answer(200, completion("Yirmi gun [1]."));
        assertThat(ask(QUESTION, null).statusCode()).isEqualTo(200);
        assertThat(COMPAT.requests().getLast().header("Authorization")).isEqualTo("Bearer " + KEY_A2);
        AuditTables.awaitCount(1, "SELECT count(*) FROM audit.event WHERE target_id = ?::uuid "
                + "AND action = 'MODEL_CONNECTION_KEY_ROTATED' AND detail_code = 'V2'", a);
        assertNoSecretInLogs();
    }

    @Test
    void hardBudget_blocksThePricedModel_with429_andNoProviderCall() throws Exception {
        String b = createAnthropic();
        route("ANSWER", b, "claude-x");
        assertThat(send("PUT", "/v1/admin/model-gateway/prices", admin, "{\"connectionId\":\"" + b + "\","
                + "\"model\":\"claude-x\",\"inputPerMillion\":3,\"outputPerMillion\":15,\"currency\":\"USD\"}")
                .statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/v1/admin/model-gateway/budgets", admin, "{\"scope\":\"CONNECTION\",\"scopeKey\":\"" + b
                + "\",\"hardLimit\":0}").statusCode()).isEqualTo(200);
        ready("izin.pdf", "topic-gateway Annual leave is twenty working days.");
        ANTHROPIC.reset();
        ANTHROPIC.answer(200, message("x [1]."));

        HttpResponse<String> blocked = ask(QUESTION, null);

        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.body()).contains("\"code\":11003");
        assertThat(ANTHROPIC.requests()).isEmpty();
        assertThat(QaTables.count("SELECT count(*) FROM qa.model_usage WHERE outcome = 'BLOCKED'")).isOne();
    }

    @Test
    void userModelChoice_isOffered_onlyWhenTheAdminAllowsIt() throws Exception {
        String a = createCompat();
        String b = createAnthropic();
        route("ANSWER", a, "compat-model", b, "claude-x");
        String user = TestIdp.bearer(account);
        assertThat(send("GET", "/v1/models", user, null).body()).contains("\"enabled\":false");
        assertThat(ask(QUESTION, b + "/claude-x").statusCode()).isEqualTo(400);

        send("PUT", "/v1/admin/model-gateway/settings", admin, "{\"userModelChoice\":true}");
        HttpResponse<String> choices = send("GET", "/v1/models", user, null);
        assertThat(choices.body()).contains("\"enabled\":true", b + "/claude-x").doesNotContain("127.0.0.1")
                .doesNotContain("Hint");

        ready("izin.pdf", "topic-gateway Annual leave is twenty working days.");
        ANTHROPIC.reset();
        ANTHROPIC.answer(200, message("Yirmi [1]."));
        COMPAT.reset();
        assertThat(ask(QUESTION, b + "/claude-x").statusCode()).isEqualTo(200);
        assertThat(COMPAT.requests()).as("the chosen model went first").isEmpty();
        assertThat(ANTHROPIC.requests()).hasSize(1);
    }

    @Test
    void trust_listsTheEnabledConnectionsAndWhereTheyGo() throws Exception {
        createAnthropic();

        HttpResponse<String> trust = send("GET", "/v1/admin/trust", admin, null);

        assertThat(trust.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(trust.body(), "$.models.known")).isTrue();
        assertThat(JsonPath.<List<String>>read(trust.body(), "$.models.connections[*].locality")).containsExactly("EXTERNAL");
        assertThat(JsonPath.<List<String>>read(trust.body(), "$.models.connections[*].host")).containsExactly("127.0.0.1");
        assertThat(trust.body()).doesNotContain(KEY_B).doesNotContain("Hint");
    }

    /** ADR-0019: every admin endpoint is verso-admin only; anonymous is 401; any signed-in user may list choices. */
    @Test
    void adminEndpoints_whenCallerIsNotAnAdmin_areDenied() throws Exception {
        String user = "Bearer " + TestIdp.IDP.tokenFor("acct-gw-" + UUID.randomUUID());
        String operator = "Bearer " + TestIdp.IDP.token().subject("acct-gw-op").realmRoles("verso-operator").build();
        UUID any = UUID.randomUUID();
        List<String[]> calls = List.of(
                new String[]{"GET", "/v1/admin/model-gateway/connections", null},
                new String[]{"POST", "/v1/admin/model-gateway/connections", compat(KEY_A)},
                new String[]{"PUT", "/v1/admin/model-gateway/connections/" + any, compat(KEY_A)},
                new String[]{"POST", "/v1/admin/model-gateway/connections/" + any + "/key", "{\"apiKey\":\"x\"}"},
                new String[]{"POST", "/v1/admin/model-gateway/connections/" + any + "/test", null},
                new String[]{"DELETE", "/v1/admin/model-gateway/connections/" + any, null},
                new String[]{"GET", "/v1/admin/model-gateway/routes", null},
                new String[]{"PUT", "/v1/admin/model-gateway/routes/ANSWER", "{\"entries\":[]}"},
                new String[]{"PUT", "/v1/admin/model-gateway/settings", "{\"userModelChoice\":true}"},
                new String[]{"GET", "/v1/admin/model-gateway/prices", null},
                new String[]{"GET", "/v1/admin/model-gateway/budgets", null},
                new String[]{"GET", "/v1/admin/model-gateway/usage", null});
        for (String[] call : calls) {
            for (String caller : List.of(user, operator)) {
                HttpResponse<String> denied = send(call[0], call[1], caller, call[2]);
                assertThat(denied.statusCode()).as("%s %s", call[0], call[1]).isEqualTo(403);
                assertThat(denied.body()).contains("\"code\":90101");
            }
            assertThat(send(call[0], call[1], null, call[2]).statusCode()).as("anonymous %s", call[1]).isEqualTo(401);
        }
        assertThat(QaTables.count("SELECT count(*) FROM qa.model_connection")).isZero();
        assertThat(send("GET", "/v1/models", user, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/v1/models", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void embeddings_haveNoRoute_andARouteNeedsAKnownModel() throws Exception {
        String a = createCompat();

        assertThat(send("PUT", "/v1/admin/model-gateway/routes/EMBEDDING", admin, "{\"entries\":[]}").body())
                .contains("\"code\":11096");
        assertThat(send("PUT", "/v1/admin/model-gateway/routes/ANSWER", admin, "{\"entries\":[{\"connectionId\":\"" + a
                + "\",\"model\":\"unknown-model\"}]}").statusCode()).isEqualTo(400);
        assertThat(send("DELETE", "/v1/admin/model-gateway/connections/" + a, admin, null).statusCode()).isEqualTo(204);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private String createCompat() throws Exception {
        COMPAT.answer(200, completion("OK"));
        HttpResponse<String> created = send("POST", "/v1/admin/model-gateway/connections", admin, compat(KEY_A));
        assertThat(created.statusCode()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.id");
    }

    private String createAnthropic() throws Exception {
        ANTHROPIC.answer(200, message("OK"));
        HttpResponse<String> created = send("POST", "/v1/admin/model-gateway/connections", admin,
                "{\"name\":\"Anthropic stub\",\"type\":\"ANTHROPIC\",\"baseUrl\":\"" + ANTHROPIC.baseUrl() + "\","
                        + "\"models\":[\"claude-x\"],\"apiKey\":\"" + KEY_B + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return JsonPath.read(created.body(), "$.id");
    }

    private static String compat(String key) {
        return "{\"name\":\"Compat stub\",\"type\":\"OPENAI_COMPATIBLE\",\"baseUrl\":\"" + COMPAT.baseUrl() + "/v1\","
                + "\"models\":[\"compat-model\"],\"apiKey\":\"" + key + "\"}";
    }

    private void route(String task, String... connectionAndModel) throws Exception {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < connectionAndModel.length; i += 2) {
            if (!entries.isEmpty()) entries.append(',');
            entries.append("{\"connectionId\":\"").append(connectionAndModel[i]).append("\",\"model\":\"")
                    .append(connectionAndModel[i + 1]).append("\"}");
        }
        HttpResponse<String> set = send("PUT", "/v1/admin/model-gateway/routes/" + task, admin,
                "{\"entries\":[" + entries + "]}");
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
    }

    private void ready(String fileName, String text) {
        DocumentRow row = documents.insert(account, fileName, DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(text));
        worker.runOnce();
        assertThat(documents.find(account, row.id()).orElseThrow().status().name()).isEqualTo("READY");
    }

    private HttpResponse<String> ask(String question, String model) throws Exception {
        String body = "{\"question\":\"" + question + "\"" + (model == null ? "" : ",\"model\":\"" + model + "\"") + "}";
        return send("POST", "/v1/questions", TestIdp.bearer(account), body);
    }

    private HttpResponse<String> send(String method, String path, String authorization, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30));
        if (authorization != null) request.header("Authorization", authorization);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** llm-rules 2.1: no key and no question text in any message, argument or throwable of any log event. */
    private void assertNoSecretInLogs() {
        for (ILoggingEvent event : logs.list) {
            String text = event.getFormattedMessage() + " " + (event.getThrowableProxy() == null ? ""
                    : event.getThrowableProxy().getMessage()) + " " + event.getMDCPropertyMap();
            for (String secret : List.of(KEY_A, KEY_A2, KEY_B, "Yillik izin kac", "synthetic outage")) {
                assertThat(text).as("log event of %s", event.getLoggerName()).doesNotContain(secret);
            }
        }
    }

    private static String completion(String text) {
        return "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1790935200,\"model\":\"compat-model\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},"
                + "\"logprobs\":null,\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2,\"total_tokens\":9}}";
    }

    private static String message(String text) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-x\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }
}
