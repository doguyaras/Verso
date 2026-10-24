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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * /v1/admin/connectors over HTTP with the real database (ADR-0038), no file server needed: verso-admin only (the 403
 * matrix, audited as ACCESS_DENIED), validation of what could leave the share or the allowed networks, an immutable
 * target collection, and a password that no response ever carries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.connector.sync-enabled=false",
                "verso.connector.allowed-networks=10.0.0.0/8,172.16.0.0/12"})
@VersoTestEnvironment
class ConnectorApiTest {

    /** A per-run value: it must appear in no response and no log. */
    private static final String PASSWORD = HexFormat.of().formatHex(new SecureRandom().generateSeed(12));

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("SECRET_CONNECTOR_SMB_PASSWORD", () -> PASSWORD);
    }

    @Value("${local.server.port}")
    int port;

    @org.springframework.beans.factory.annotation.Autowired
    com.verso.connector.repository.ConnectorRepository connectors;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String adminSub = "acct-conn-admin-" + suffix;
    private final String admin = TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    private final String userSub = "acct-conn-user-" + suffix;
    private final String user = TestIdp.IDP.token().subject(userSub).build();

    @BeforeEach
    void setUp() {
        AuditTables.update("DELETE FROM connector.connector");
    }

    /** Leaves no connector or collection behind for the other test classes sharing the database. */
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        AuditTables.update("DELETE FROM connector.connector");
        AuditTables.update("DELETE FROM document.collection WHERE name LIKE 'Bağlayıcı %'");
    }

    /** ADR-0019: every endpoint refuses a user and an operator (403, audited), and an anonymous caller (401). */
    @Test
    void endpoints_whenCallerIsNotAnAdmin_areDeniedAndAudited() throws Exception {
        UUID id = UUID.fromString(JsonPath.read(create(body("Matris " + suffix, collection(), "")).body(), "$.id"));
        String operator = TestIdp.IDP.token().subject("acct-conn-op-" + suffix).realmRoles("verso-operator", "verso-user")
                .build();
        List<String[]> calls = List.of(new String[]{"GET", "/v1/admin/connectors", null},
                new String[]{"POST", "/v1/admin/connectors", body("x", UUID.randomUUID(), "")},
                new String[]{"GET", "/v1/admin/connectors/" + id, null},
                new String[]{"PUT", "/v1/admin/connectors/" + id, body("y", UUID.randomUUID(), "")},
                new String[]{"DELETE", "/v1/admin/connectors/" + id, null},
                new String[]{"POST", "/v1/admin/connectors/" + id + "/sync", null},
                new String[]{"GET", "/v1/admin/connectors/" + id + "/runs", null},
                new String[]{"GET", "/v1/admin/connectors/" + id + "/items", null});
        for (String[] call : calls) {
            HttpResponse<String> denied = send(user, call[0], call[1], call[2]);
            assertThat(denied.statusCode()).as(call[0] + " " + call[1]).isEqualTo(403);
            assertThat(denied.body()).contains("\"code\":90101");
            assertThat(send(operator, call[0], call[1], call[2]).statusCode()).as("operator " + call[1]).isEqualTo(403);
            assertThat(send(null, call[0], call[1], call[2]).statusCode()).as("anonymous " + call[1]).isEqualTo(401);
        }
        assertThat(AuditTables.awaitCount(calls.size(), "select count(*) from audit.event where actor_sub = ? and action = 'ACCESS_DENIED' "
                + "and detail_code = 'ADMIN_CONNECTOR'", userSub)).isEqualTo(calls.size());
        assertThat(send(admin, "GET", "/v1/admin/connectors/" + id, null).body()).contains("\"health\":\"NEVER_SYNCED\"");
    }

    @Test
    void create_whenValid_returnsTheConnectorWithoutAnyPassword_andAuditsIt() throws Exception {
        UUID collection = collection();

        HttpResponse<String> created = create(body("Paylaşım " + suffix, collection, "IK\\\\Politikalar\\\\"));

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        String id = JsonPath.read(created.body(), "$.id");
        assertThat(created.headers().firstValue("Location")).hasValue("/v1/admin/connectors/" + id);
        assertThat((String) JsonPath.read(created.body(), "$.pathPrefix")).isEqualTo("IK/Politikalar");
        assertThat((Integer) JsonPath.read(created.body(), "$.port")).isEqualTo(445);
        assertThat((String) JsonPath.read(created.body(), "$.credentialRef")).isEqualTo("SECRET_CONNECTOR_SMB_PASSWORD");
        assertThat((Integer) JsonPath.read(created.body(), "$.maxFileBytes")).isEqualTo(1_000_000);
        assertThat((String) JsonPath.read(created.body(), "$.health")).isEqualTo("NEVER_SYNCED");
        for (HttpResponse<String> read : List.of(created, send(admin, "GET", "/v1/admin/connectors/" + id, null),
                send(admin, "GET", "/v1/admin/connectors", null))) {
            assertThat(read.body()).doesNotContain(PASSWORD).doesNotContainIgnoringCase("\"password\"");
        }
        AuditTables.awaitCount(1, "select count(*) from audit.event where actor_sub = ? and action = 'CONNECTOR_CREATED'",
                adminSub);
        assertThat(AuditTables.strings("select target_type || '/' || target_id from audit.event where actor_sub = ? "
                + "and action = 'CONNECTOR_CREATED'", adminSub)).containsExactly("CONNECTOR/" + id);
    }

    /** Path traversal, other secrets, public hosts, oversized files and bad globs never reach the database. */
    @Test
    void create_whenAFieldCouldLeaveTheShareOrTheNetwork_isRefused() throws Exception {
        UUID collection = collection();
        record Case(String json, int status, int code) {
        }
        for (Case c : List.of(
                new Case(body("t1", collection, "../etc"), 400, 14003),
                new Case(body("t2", collection, "IK/../../Finans"), 400, 14003),
                new Case(body("t3", collection, "C:\\\\Windows"), 400, 14003),
                new Case(body("t4", collection, "docs:stream"), 400, 14003),
                new Case(body("t5", collection, "").replace("\"10.20.30.40\"", "\"8.8.8.8\""), 400, 14004),
                new Case(body("t6", collection, "").replace("\"10.20.30.40\"", "\"127.0.0.1\""), 400, 14004),
                new Case(body("t7", collection, "").replace("\"10.20.30.40\"", "\"169.254.169.254\""), 400, 14004),
                new Case(body("t8", collection, "").replace("SECRET_CONNECTOR_SMB_PASSWORD", "SECRET_DB_DOCUMENT_PASSWORD"),
                        400, 90000),
                new Case(body("t9", collection, "").replace("\"fs01\"", "\"fs01/../x\""), 400, 90000),
                new Case(body("t10", collection, "").replace("\"maxFileBytes\":1000000", "\"maxFileBytes\":999999999"),
                        400, 14010),
                new Case(body("t11", collection, "").replace("\"excludeGlobs\":[]", "\"excludeGlobs\":[\"{a,b\"]"), 400,
                        14009),
                new Case(body("t12", UUID.randomUUID(), ""), 400, 14005))) {
            HttpResponse<String> refused = send(admin, "POST", "/v1/admin/connectors", c.json());
            assertThat(refused.statusCode()).as(c.json()).isEqualTo(c.status());
            if (c.code() != 90000) assertThat(refused.body()).as(c.json()).contains("\"code\":" + c.code());
        }
        assertThat(AuditTables.count("select count(*) from connector.connector")).isZero();
    }

    @Test
    void update_whenTheCollectionChangesOrTheNameIsTaken_isRefused_otherwiseReplacesTheSettings() throws Exception {
        UUID collection = collection();
        String id = JsonPath.read(create(body("A " + suffix, collection, "")).body(), "$.id");
        create(body("B " + suffix, collection, ""));

        assertThat(send(admin, "PUT", "/v1/admin/connectors/" + id, body("A " + suffix, collection(), "")).body())
                .contains("\"code\":14006");
        assertThat(send(admin, "PUT", "/v1/admin/connectors/" + id, body("b " + suffix, collection, "")).body())
                .contains("\"code\":14002");
        HttpResponse<String> updated = send(admin, "PUT", "/v1/admin/connectors/" + id,
                body("A2 " + suffix, collection, "Yeni").replace("\"scheduleMinutes\":60", "\"scheduleMinutes\":15"));
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(updated.body(), "$.scheduleMinutes")).isEqualTo(15);
        assertThat(send(admin, "PUT", "/v1/admin/connectors/" + UUID.randomUUID(), body("Z", collection, "")).statusCode())
                .isEqualTo(404);
    }

    @Test
    void deleteAndSync_whenRequested_areAcceptedOnce_andADeletedConnectorCannotBeSynced() throws Exception {
        String id = JsonPath.read(create(body("Silinecek " + suffix, collection(), "")).body(), "$.id");

        assertThat(send(admin, "POST", "/v1/admin/connectors/" + id + "/sync", "{\"confirmMassDeletion\":true}")
                .statusCode()).isEqualTo(202);
        assertThat(AuditTables.count("select count(*) from connector.connector where id = ? and mass_deletion_confirmed",
                UUID.fromString(id))).isEqualTo(1);
        assertThat(send(admin, "DELETE", "/v1/admin/connectors/" + id, null).statusCode()).isEqualTo(202);
        assertThat(send(admin, "DELETE", "/v1/admin/connectors/" + id, null).statusCode()).as("idempotent").isEqualTo(202);
        assertThat(send(admin, "GET", "/v1/admin/connectors/" + id, null).body()).contains("\"health\":\"DELETING\"");
        assertThat(send(admin, "POST", "/v1/admin/connectors/" + id + "/sync", null).body()).contains("\"code\":14007");
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where actor_sub = ? and action = 'CONNECTOR_DELETED'",
                adminSub)).as("one deletion, one row").isEqualTo(1);
        assertThat(send(admin, "GET", "/v1/admin/connectors/" + UUID.randomUUID() + "/runs", null).body())
                .contains("\"code\":14001");
        assertThat(send(admin, "GET", "/v1/admin/connectors?size=500", null).body()).contains("\"code\":14011");
    }

    /**
     * AGENTS.md 10: two instances claiming at once get different connectors, a held lease is not claimed again, and a
     * claim holder whose lease was taken over can neither renew nor write a result.
     */
    @Test
    void claimDue_whenTwoInstancesClaimAtOnce_getDisjointConnectors_andAStaleHolderCannotFinish() throws Exception {
        UUID collection = collection();
        create(body("C1 " + suffix, collection, ""));
        create(body("C2 " + suffix, collection, ""));
        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<java.util.Optional<com.verso.connector.repository.ConnectorRow>>> claims =
                    new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                claims.add(pool.submit(() -> {
                    start.await();
                    return connectors.claimDue(UUID.randomUUID(), Duration.ofMinutes(10));
                }));
            }
            UUID first = claims.get(0).get().orElseThrow().id();
            UUID second = claims.get(1).get().orElseThrow().id();
            assertThat(first).isNotEqualTo(second);
        } finally {
            pool.shutdownNow();
        }
        assertThat(connectors.claimDue(UUID.randomUUID(), Duration.ofMinutes(10))).as("both leases are held").isEmpty();

        UUID stale = UUID.randomUUID();
        AuditTables.update("UPDATE connector.connector SET claim_token = ?, locked_until = now() - interval '1 second'",
                stale);
        UUID taker = UUID.randomUUID();
        UUID taken = connectors.claimDue(taker, Duration.ofMinutes(10)).orElseThrow().id();
        assertThat(connectors.renew(taken, stale, Duration.ofMinutes(10))).isFalse();
        assertThat(connectors.finish(taken, stale, java.time.Instant.now(),
                new com.verso.connector.repository.ConnectorRepository.Finish(
                        com.verso.connector.repository.ConnectorRepository.Finish.Result.SUCCESS, null,
                        Duration.ofMinutes(60), false))).isFalse();
        assertThat(connectors.renew(taken, taker, Duration.ofMinutes(10))).isTrue();
    }

    private HttpResponse<String> create(String json) throws Exception {
        HttpResponse<String> response = send(admin, "POST", "/v1/admin/connectors", json);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return response;
    }

    private UUID collection() throws Exception {
        HttpResponse<String> response = send(admin, "POST", "/v1/collections",
                "{\"name\":\"Bağlayıcı " + UUID.randomUUID().toString().substring(0, 8) + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    static String body(String name, UUID collection, String prefix) {
        return "{\"name\":\"" + name + "\",\"host\":\"10.20.30.40\",\"share\":\"fs01\",\"pathPrefix\":\"" + prefix
                + "\",\"username\":\"svc-verso\",\"credentialRef\":\"SECRET_CONNECTOR_SMB_PASSWORD\",\"collectionId\":\""
                + collection + "\",\"includeGlobs\":[],\"excludeGlobs\":[],\"maxFileBytes\":1000000,"
                + "\"scheduleMinutes\":60}";
    }

    private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
