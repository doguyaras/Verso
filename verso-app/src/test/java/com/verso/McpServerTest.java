package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.AuditTables;
import com.verso.support.Multipart;
import com.verso.support.QaTables;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
 * The read-only MCP server end to end (ADR-0031) on a real port, the real database and the SDK's own client:
 *
 * <ul>
 *   <li>protocol: initialize, tools/list (two read-only tools, schemas, the untrusted-content statement),
 *       tools/call of verso_search and verso_fetch with the search/fetch shape ChatGPT expects;</li>
 *   <li>security matrix: no token is 401 with the RFC 9728 pointer; a panel token (aud verso-api) is 401 here and an
 *       MCP token is 401 on /v1; group A never gets collection B's passages; fetching an unreadable passage looks
 *       exactly like fetching one that does not exist; a browser Origin is refused;</li>
 *   <li>limits: query, top_k, body size and concurrency; log privacy; one audit row per tool call, ids only.</li>
 * </ul>
 *
 * The disabled case (the default) is McpDisabledTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "VERSO_MCP_ENABLED=true"})
@VersoTestEnvironment
class McpServerTest {

    private static final String RESOURCE_METADATA =
            "resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource/mcp\"";
    private static final String MARKER = "MCPSECRETMARKER";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestionWorker worker;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String admin = TestIdp.IDP.token().subject("acct-mcp-admin-" + suffix).realmRoles("verso-admin").build();
    private final String aliceSub = "acct-mcp-alice-" + suffix;
    private final String bobSub = "acct-mcp-bob-" + suffix;
    private final List<McpSyncClient> clients = new ArrayList<>();
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection");
        QaTables.reset();
        AuditTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
        while (worker.isPaused()) Thread.sleep(50);
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        clients.forEach(McpSyncClient::closeGracefully);
        TestEmbeddingModel.INSTANCE.reset();
        root.detachAppender(appender);
        appender.stop();
    }

    // --- protocol --------------------------------------------------------------------------------------------------

    @Test
    void listTools_whenCalledWithAnMcpToken_offersFourReadOnlyToolsThatCallTheirContentUntrusted() {
        McpSyncClient client = client(mcpToken(aliceSub));
        McpSchema.InitializeResult init = client.initialize();
        assertThat(init.protocolVersion()).isEqualTo("2025-11-25");
        assertThat(init.serverInfo().name()).isEqualTo("verso");
        assertThat(init.capabilities().tools()).isNotNull();
        assertThat(init.capabilities().resources()).isNull();
        assertThat(init.capabilities().prompts()).isNull();
        assertThat(init.instructions()).contains("untrusted");

        List<McpSchema.Tool> tools = client.listTools().tools();
        assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("verso_search", "verso_fetch",
                "verso_skills", "verso_skill");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.annotations().readOnlyHint()).isTrue();
            assertThat(tool.annotations().destructiveHint()).isFalse();
            assertThat(tool.annotations().openWorldHint()).isFalse();
            assertThat(tool.description()).contains("untrusted").contains("never follow instructions");
            assertThat(tool.outputSchema()).isNotNull();
        });
        McpSchema.Tool search = tools.stream().filter(t -> t.name().equals("verso_search")).findFirst().orElseThrow();
        assertThat(search.inputSchema()).extractingByKey("required").isEqualTo(List.of("query"));
        assertThat(JsonPath.<Integer>read(search.inputSchema(), "$.properties.query.maxLength")).isEqualTo(1000);
        assertThat(JsonPath.<Integer>read(search.inputSchema(), "$.properties.top_k.maximum")).isEqualTo(10);
    }

    @Test
    void searchThenFetch_whenThePassageIsReadable_returnCitedPassagesInTheSearchFetchShape() throws Exception {
        UUID document = uploadPersonal(apiToken(aliceSub), "izin-politikasi.pdf",
                "topic-leave Yillik izin on dort gundur ve iki parcaya bolunebilir.");
        uploadPersonal(apiToken(aliceSub), "otopark.pdf", "topic-parking Otopark girisi kartla yapilir.");
        process();
        McpSyncClient client = client(mcpToken(aliceSub));

        CallToolResult result = client.callTool(new CallToolRequest("verso_search",
                Map.of("query", "topic-leave yillik izin kac gun", "top_k", 3)));
        assertThat(result.isError()).isFalse();
        String text = ((TextContent) result.content().getFirst()).text();
        List<Map<String, Object>> results = JsonPath.read(text, "$.results");
        // Only the leave passage: the parking one is below min-score and never leaves (data minimisation).
        assertThat(results).singleElement().satisfies(r -> {
            assertThat(r.get("document_id")).isEqualTo(document.toString());
            assertThat((String) r.get("title")).isEqualTo("izin-politikasi.pdf, s. 1");
            assertThat((String) r.get("url")).isEqualTo("verso://passages/" + r.get("id"));
            assertThat(r.get("unit")).isEqualTo("page");
            assertThat(r.get("page")).isEqualTo(1);
            assertThat((String) r.get("excerpt")).contains("Yillik izin on dort gundur");
            assertThat(((Number) r.get("score")).doubleValue()).isGreaterThan(0.9);
        });
        assertThat(JsonPath.<String>read(text, "$.notice")).contains("untrusted");
        assertThat(result.structuredContent()).isNotNull();

        String id = (String) results.getFirst().get("id");
        CallToolResult fetched = client.callTool(new CallToolRequest("verso_fetch", Map.of("id", id)));
        assertThat(fetched.isError()).isFalse();
        String passage = ((TextContent) fetched.content().getFirst()).text();
        assertThat(JsonPath.<String>read(passage, "$.id")).isEqualTo(id);
        assertThat(JsonPath.<String>read(passage, "$.text")).contains("iki parcaya bolunebilir");
        assertThat(JsonPath.<String>read(passage, "$.title")).isEqualTo("izin-politikasi.pdf, s. 1");
        assertThat(JsonPath.<String>read(passage, "$.metadata.document_id")).isEqualTo(document.toString());
        // The url form is accepted as well (a client may pass the citation back).
        assertThat(client.callTool(new CallToolRequest("verso_fetch", Map.of("id", "verso://passages/" + id))).isError())
                .isFalse();

        // One audit row per tool call: who, which documents, the outcome word; never the query.
        assertThat(AuditTables.strings("SELECT detail_code FROM audit.event WHERE action = 'MCP_SEARCH' AND actor_sub = ?",
                aliceSub)).containsExactly("RESULTS");
        assertThat(AuditTables.count("SELECT count(*) FROM audit.event WHERE action = 'MCP_FETCH' AND actor_sub = ? "
                + "AND detail_code = 'FOUND' AND target_type = 'PASSAGE' AND target_id = ?::uuid "
                + "AND citation_document_ids = ARRAY[?::uuid]", aliceSub, id, document.toString())).isEqualTo(2);
        assertThat(AuditTables.strings("SELECT actor_roles::text FROM audit.event WHERE actor_sub = ?", aliceSub))
                .allMatch(roles -> roles.equals("{verso-user}"));
    }

    // --- security matrix ---------------------------------------------------------------------------------------------

    @Test
    void mcp_withoutAToken_is401WithTheProtectedResourceMetadataPointer() throws Exception {
        HttpResponse<String> response = post(null, initialize(), null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(challenge -> assertThat(challenge)
                .startsWith("Bearer ").contains(RESOURCE_METADATA).contains("scope=\"verso-mcp\"")
                .doesNotContain("error="));
        assertThat(response.body()).contains("\"code\":90100");
        // llm-rules 1.3: the mode is stated on MCP responses too, refusals included.
        assertThat(response.headers().firstValue("X-Rag-Mode")).hasValue("local");

        // The metadata itself is public: the authorization server and the scope to ask for (RFC 9728).
        HttpResponse<String> metadata = http.send(HttpRequest.newBuilder(
                URI.create(base() + "/.well-known/oauth-protected-resource/mcp")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(metadata.body(), "$.resource")).isEqualTo("http://localhost:8080/mcp");
        assertThat(JsonPath.<List<String>>read(metadata.body(), "$.authorization_servers"))
                .containsExactly(com.verso.platform.security.testing.TestIdentityProvider.ISSUER);
        assertThat(JsonPath.<List<String>>read(metadata.body(), "$.scopes_supported")).containsExactly("verso-mcp");
        assertThat(JsonPath.<List<String>>read(metadata.body(), "$.bearer_methods_supported")).containsExactly("header");
        assertThat(metadata.body()).doesNotContain("registration_endpoint").doesNotContain("tls_client_certificate");
        assertThat(http.send(HttpRequest.newBuilder(URI.create(base() + "/.well-known/oauth-protected-resource"))
                .GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    void tokens_whenUsedOnTheOtherSurface_are401_aPanelTokenHereAndAnMcpTokenOnTheApi() throws Exception {
        HttpResponse<String> panelTokenHere = post(apiToken(aliceSub), initialize(), null);
        assertThat(panelTokenHere.statusCode()).isEqualTo(401);
        assertThat(panelTokenHere.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(challenge ->
                assertThat(challenge).contains("error=\"invalid_token\"").contains(RESOURCE_METADATA));
        // A token for both audiences is still accepted here: aud must contain verso-mcp, as /v1 requires verso-api.
        String both = TestIdp.IDP.token().subject(aliceSub).audience("verso-api", "verso-mcp").build();
        assertThat(post(both, initialize(), null).statusCode()).isEqualTo(200);
        // An unsigned or foreign-issuer token with the right audience is refused as well.
        String foreign = TestIdp.IDP.token().subject(aliceSub).audience("verso-mcp").issuer("https://evil.example").build();
        assertThat(post(foreign, initialize(), null).statusCode()).isEqualTo(401);

        HttpResponse<String> mcpTokenOnApi = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/documents"))
                .header("Authorization", "Bearer " + mcpToken(aliceSub)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(mcpTokenOnApi.statusCode()).isEqualTo(401);
    }

    @Test
    void groupA_neverGetsCollectionBPassages_andFetchOfAnUnreadablePassageLooksLikeAMissingOne() throws Exception {
        UUID hukuk = collection("Hukuk " + suffix);
        UUID ik = collection("IK " + suffix);
        grant(hukuk, "/hukuk");
        grant(ik, "/ik");
        UUID contract = uploadInto(hukuk, "sozlesme.pdf", "topic-policy Sozlesme fesih suresi otuz gundur.");
        UUID payroll = uploadInto(ik, "bordro.pdf", "topic-policy Bordro her ayin besinde odenir.");
        process();
        McpSyncClient alice = client(mcpToken(aliceSub, "/hukuk"));
        McpSyncClient bob = client(mcpToken(bobSub, "/ik"));

        // The same topic in both collections, equally similar: Alice still gets Hukuk's passage only.
        List<Map<String, Object>> aliceResults = results(alice.callTool(new CallToolRequest("verso_search",
                Map.of("query", "topic-policy fesih", "top_k", 10))));
        assertThat(aliceResults).extracting(r -> r.get("document_id")).containsOnly(contract.toString());
        assertThat(aliceResults).allSatisfy(r -> assertThat(r.get("collection")).isEqualTo("Hukuk " + suffix));
        List<Map<String, Object>> bobResults = results(bob.callTool(new CallToolRequest("verso_search",
                Map.of("query", "topic-policy fesih", "top_k", 10))));
        assertThat(bobResults).extracting(r -> r.get("document_id")).containsOnly(payroll.toString());
        String bobsPassage = (String) bobResults.getFirst().get("id");

        // Bob's passage id through Alice: the very answer an unknown id and a malformed id get.
        CallToolResult unreadable = alice.callTool(new CallToolRequest("verso_fetch", Map.of("id", bobsPassage)));
        CallToolResult unknown = alice.callTool(new CallToolRequest("verso_fetch",
                Map.of("id", UUID.randomUUID().toString())));
        CallToolResult malformed = alice.callTool(new CallToolRequest("verso_fetch", Map.of("id", "../../etc")));
        for (CallToolResult result : List.of(unreadable, unknown, malformed)) {
            assertThat(result.isError()).isTrue();
            assertThat(result.structuredContent()).isNull();
            assertThat(((TextContent) result.content().getFirst()).text())
                    .isEqualTo("Passage not found. Use an id returned by verso_search.");
        }
        assertThat(AuditTables.strings("SELECT detail_code FROM audit.event WHERE action = 'MCP_FETCH' AND actor_sub = ?",
                aliceSub)).containsOnly("NOT_FOUND").hasSize(3);
        assertThat(AuditTables.count("SELECT count(*) FROM audit.event WHERE actor_sub = ? "
                + "AND ?::uuid = ANY(citation_document_ids)", aliceSub, payroll.toString())).isZero();

        // Without groups (a token whose groups claim is missing), neither collection.
        McpSyncClient carol = client(mcpToken("acct-mcp-carol-" + suffix));
        assertThat(results(carol.callTool(new CallToolRequest("verso_search", Map.of("query", "topic-policy fesih")))))
                .isEmpty();
    }

    @Test
    void mcp_whenTheRequestCarriesABrowserOrigin_isRefused() throws Exception {
        HttpResponse<String> response = post(mcpToken(aliceSub), initialize(), "http://evil.example");
        assertThat(response.statusCode()).isEqualTo(403);
    }

    // --- limits --------------------------------------------------------------------------------------------------------

    @Test
    void limits_queryTopKAndBodySize_areEnforcedAndResultsAreBounded() throws Exception {
        uploadPersonal(apiToken(aliceSub), "uzun.pdf", "topic-long " + "Uzun bir paragraf metni burada yer alir. ".repeat(40));
        process();
        McpSyncClient client = client(mcpToken(aliceSub));

        CallToolResult tooLong = client.callTool(new CallToolRequest("verso_search", Map.of("query", "a".repeat(1001))));
        assertThat(tooLong.isError()).isTrue();
        // Refused by the input schema (maxLength) before the tool runs; the tool checks again (defence in depth).
        assertThat(((TextContent) tooLong.content().getFirst()).text()).contains("query").doesNotContain("aaaa");
        for (Object topK : List.of(0, 11, 2.5, "3")) {
            CallToolResult bad = client.callTool(new CallToolRequest("verso_search", Map.of("query", "topic-long", "top_k", topK)));
            assertThat(bad.isError()).as("top_k %s", topK).isTrue();
        }
        assertThat(client.callTool(new CallToolRequest("verso_search", Map.of())).isError()).isTrue();

        List<Map<String, Object>> results = results(client.callTool(new CallToolRequest("verso_search",
                Map.of("query", "topic-long paragraf", "top_k", 1))));
        assertThat(results).hasSize(1);
        String excerpt = (String) results.getFirst().get("excerpt");
        assertThat(excerpt.codePointCount(0, excerpt.length())).isLessThanOrEqualTo(600);
        assertThat(excerpt).endsWith("…").doesNotContain("\n");

        // A body above 32 KB is refused before it is read.
        String big = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"pad\":\"" + "x".repeat(40_000) + "\"}}";
        HttpResponse<String> tooLarge = post(mcpToken(aliceSub), big, null);
        assertThat(tooLarge.statusCode()).isEqualTo(413);
        assertThat(tooLarge.body()).contains("\"code\":90014");
    }

    @Test
    void concurrency_whenTheInstanceSlotsAreTaken_answers429AtOnce() throws Exception {
        uploadPersonal(apiToken(aliceSub), "izin.pdf", "topic-leave Yillik izin on dort gundur.");
        process();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        TestEmbeddingModel.INSTANCE.onCall(() -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        String token = mcpToken(aliceSub);
        List<CompletableFuture<HttpResponse<String>>> running = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            running.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return post(token, call("verso_search", "{\"query\":\"topic-leave izin\"}"), null);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        // Both slots (verso.mcp.max-concurrent-calls, 2) are held by calls waiting on the model.
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        HttpResponse<String> third = post(token, initialize(), null);
        release.countDown();
        assertThat(third.statusCode()).isEqualTo(429);
        assertThat(third.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(third.body()).contains("\"code\":90102");
        for (CompletableFuture<HttpResponse<String>> call : running) {
            assertThat(call.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        assertThat(post(token, initialize(), null).statusCode()).as("the slots are free again").isEqualTo(200);
    }

    // --- log privacy -----------------------------------------------------------------------------------------------------

    @Test
    void logs_whenQueriesAndBrokenBodiesCarryText_neverContainThatText() throws Exception {
        uploadPersonal(apiToken(aliceSub), "gizli-" + MARKER.toLowerCase() + ".pdf", "topic-leave " + MARKER + " izin metni.");
        process();
        McpSyncClient client = client(mcpToken(aliceSub));
        List<Map<String, Object>> results = results(client.callTool(new CallToolRequest("verso_search",
                Map.of("query", "topic-leave " + MARKER))));
        assertThat(results).isNotEmpty();
        client.callTool(new CallToolRequest("verso_fetch", Map.of("id", results.getFirst().get("id"))));
        client.callTool(new CallToolRequest("verso_fetch", Map.of("id", MARKER)));
        client.callTool(new CallToolRequest("verso_search", Map.of("query", MARKER.repeat(100))));
        // Broken JSON and a broken JSON-RPC message: the parser's error messages quote the input.
        assertThat(post(mcpToken(aliceSub), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":" + MARKER + "}", null).statusCode())
                .isEqualTo(400);
        post(mcpToken(aliceSub), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"" + MARKER + "\",\"params\":{}}", null);
        post(mcpToken(aliceSub), call("verso_" + MARKER, "{}"), null);

        assertThat(appender.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContainIgnoringCase(MARKER);
            assertThat(String.valueOf(event.getMDCPropertyMap())).doesNotContainIgnoringCase(MARKER);
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                assertThat(String.valueOf(t.getMessage())).doesNotContainIgnoringCase(MARKER);
            }
        });
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(m -> assertThat(m).contains("MCP tool called: tool=verso_search outcome=results"))
                .anySatisfy(m -> assertThat(m).contains("MCP request rejected: code=MCP_TRANSPORT reason=STATUS_400"));
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private String base() {
        return "http://localhost:" + port;
    }

    private static String mcpToken(String subject, String... groups) {
        var builder = TestIdp.IDP.token().subject(subject).audience("verso-mcp");
        if (groups.length > 0) builder.claim("groups", List.of(groups));
        return builder.build();
    }

    private static String apiToken(String subject) {
        return TestIdp.IDP.token().subject(subject).build();
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(CallToolResult result) {
        assertThat(result.isError()).as(String.valueOf(result.content())).isFalse();
        return (List<Map<String, Object>>) ((Map<String, Object>) result.structuredContent()).get("results");
    }

    private static String initialize() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
    }

    private static String call(String tool, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":" + arguments + "}}";
    }

    private HttpResponse<String> post(String token, String body, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (origin != null) request.header("Origin", origin);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private UUID collection(String name) throws Exception {
        HttpResponse<String> response = send(admin, "POST", "/v1/collections", "{\"name\":\"" + name + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private void grant(UUID collection, String group) throws Exception {
        HttpResponse<String> response = send(admin, "POST", "/v1/collections/" + collection + "/grants",
                "{\"principalType\":\"GROUP\",\"principal\":\"" + group + "\"}");
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
    }

    private UUID uploadInto(UUID collection, String fileName, String text) throws Exception {
        return upload(admin, "/v1/documents?collectionId=" + collection, fileName, text);
    }

    private UUID uploadPersonal(String token, String fileName, String text) throws Exception {
        return upload(token, "/v1/documents", fileName, text);
    }

    private UUID upload(String token, String path, String fileName, String text) throws Exception {
        Multipart body = Multipart.pdf(fileName, TestPdfs.pages(text));
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", body.contentType())
                .timeout(Duration.ofSeconds(30)).POST(body.body()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private void process() {
        for (int i = 0; i < 5; i++) worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document WHERE status <> 'READY'", Integer.class))
                .isZero();
    }

    private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
