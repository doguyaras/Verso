package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.verso.connector.worker.ConnectorSyncWorker;
import com.verso.support.AuditTables;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The SharePoint Online connector end to end against a stub Microsoft Graph and Entra token endpoint (ADR-0045); no
 * tenant. The stub runs in this JVM on a private (RFC 1918) address of this host. SharePoint is cloud only, so the
 * application runs in cloud mode with external connector egress on and the SharePoint hosts allowlisted; only the stub
 * switch (verso.connector.sharepoint.require-https=false) lets Graph and Entra sit on a private literal instead of the
 * global endpoints.
 *
 * <p>Covered: client-credentials sign-in (client secret), add, update and delete reach the collection (the delete
 * through the delta's deleted facet), a file with unique permissions is skipped, 410 Gone resyncs from scratch, a
 * throttled 429 is waited out, a download redirect to a host outside the allowlist is refused, the download URL never
 * gets the token, the secret and the access token appear in no log line and no response, the API refuses a site outside
 * the SharePoint hosts and foreign fields, and only verso-admin reaches the endpoints. Local mode: SharePointLocalModeTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.connector.sync-enabled=false",
                "VERSO_AI_MODE=cloud", "VERSO_CHAT_PROVIDER=openai", "CLOUD_CHAT_MODEL=gpt-test",
                "spring.ai.openai.api-key=test-openai-key",
                "verso.connector.allowed-networks=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16",
                "verso.connector.external-egress.enabled=true",
                "verso.connector.external-egress.allowed-hosts=graph.microsoft.com,login.microsoftonline.com,*.sharepoint.com",
                "verso.connector.sharepoint.require-https=false",
                "verso.connector.sharepoint.default-tenant-id=" + SharePointSyncTest.TENANT,
                "verso.connector.sharepoint.max-attempts=3", "verso.connector.sharepoint.retry-base=10ms",
                "verso.connector.sharepoint.max-retry-wait=2s"})
@VersoTestEnvironment
class SharePointSyncTest {

    static final String TENANT = "0a1b2c3d-4e5f-4061-8273-94a5b6c7d8e9";
    private static final String CLIENT = "9e8d7c6b-5a49-4837-a625-14f3e2d1c0b9";
    private static final String SECRET = "sp-secret-" + HexFormat.of().formatHex(new SecureRandom().generateSeed(16));
    private static final Stub STUB = new Stub();

    @DynamicPropertySource
    static void sharePoint(DynamicPropertyRegistry registry) {
        registry.add("SECRET_CONNECTOR_SHAREPOINT_SECRET", () -> SECRET);
        registry.add("verso.connector.sharepoint.graph-url", () -> "http://" + STUB.address() + "/v1.0");
        registry.add("verso.connector.sharepoint.authority", () -> "http://" + STUB.address());
    }

    @AfterAll
    static void stopStub() {
        STUB.server.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    ConnectorSyncWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String admin = TestIdp.IDP.token().subject("acct-spo-admin-" + suffix).realmRoles("verso-admin").build();
    private final List<HttpResponse<String>> responses = new ArrayList<>();
    private Logger root;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        AuditTables.update("DELETE FROM connector.connector");
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection WHERE name LIKE 'SharePoint alanı %'");
        STUB.reset();
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        AuditTables.update("DELETE FROM connector.connector");
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection WHERE name LIKE 'SharePoint alanı %'");
        root.detachAppender(logs);
        logs.stop();
        String all = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).as("the client secret reaches no log line").doesNotContain(SECRET);
        assertThat(all).as("nor the access token").doesNotContain(Stub.BEARER);
        assertThat(all).as("no file name or content in the log").doesNotContain("politika-" + suffix)
                .doesNotContain("maaş tablosu").doesNotContain("contoso.sharepoint.com/sites/hr");
        for (HttpResponse<String> response : responses) {
            assertThat(response.body()).as("nor a response").doesNotContain(SECRET).doesNotContain(Stub.BEARER);
        }
        assertThat(STUB.graphAuthorizations).as("Graph requests carried the app token").allMatch(("Bearer " + Stub.BEARER)::equals);
        assertThat(STUB.downloadAuthorizations).as("the pre-authenticated download URL never gets the token")
                .allMatch("null"::equals);
    }

    @Test
    void sync_whenFilesAreAddedChangedRestrictedAndDeleted_mirrorsThemThroughTheDelta() throws Exception {
        STUB.folder("F1", "Politikalar", Stub.ROOT);
        STUB.file("A1", "politika-" + suffix + ".md", "F1", "# İzin\n\nYıllık izin 14 gündür.");
        STUB.file("B2", "notlar-" + suffix + ".txt", Stub.ROOT, "Toplantı notları.");
        STUB.file("C3", "gizli-" + suffix + ".md", Stub.ROOT, "maaş tablosu");
        STUB.unique("C3");
        STUB.file("D4", "resim.png", Stub.ROOT, "png");
        UUID collection = collection();
        UUID connector = create(collection);

        syncOk(connector);

        assertThat(fileNames(collection)).containsExactlyInAnyOrder("politika-" + suffix + ".md", "notlar-" + suffix + ".txt");
        assertThat(itemStates(connector)).containsExactlyInAnyOrder("drives/b!d1/items/A1=SYNCED",
                "drives/b!d1/items/B2=SYNCED", "drives/b!d1/items/C3=SKIPPED:RESTRICTED");
        assertThat(lastRun(connector)).isEqualTo("SUCCEEDED/null/2/0/0/FULL/0");
        assertThat(STUB.requests).as("the restricted file's content is never fetched").noneMatch(p -> p.contains("C3/content"));
        assertThat(STUB.tokenForms).allMatch(f -> f.contains("grant_type=client_credentials")
                && f.contains("client_id=" + CLIENT) && f.contains("client_secret="));
        assertThat(AuditTables.strings("select count(*)::text from connector.sharepoint_drive where connector_id = ?", connector))
                .containsExactly("1");

        STUB.change("B2", "Toplantı notları, ikinci sürüm.");
        STUB.delete("A1");
        syncOk(connector);

        assertThat(fileNames(collection)).containsExactly("notlar-" + suffix + ".txt");
        assertThat(content(document(collection, "notlar-" + suffix + ".txt"))).contains("ikinci sürüm");
        assertThat(lastRun(connector)).as("incremental: the change and the deleted facet")
                .isEqualTo("SUCCEEDED/null/0/1/1/INCREMENTAL/0");
        assertThat(STUB.requests).anyMatch(p -> p.contains("/root/delta") && p.contains("token="));

        HttpResponse<String> view = send(admin, "GET", "/v1/admin/connectors/" + connector, null);
        assertThat((String) JsonPath.read(view.body(), "$.type")).isEqualTo("SHAREPOINT");
        assertThat((String) JsonPath.read(view.body(), "$.sharePoint.tenantId")).isEqualTo(TENANT);
        assertThat((String) JsonPath.read(view.body(), "$.sharePoint.authMethod")).isEqualTo("CLIENT_SECRET");
        assertThat((Boolean) JsonPath.read(view.body(), "$.sharePoint.skipUniquePermissions")).isTrue();
        assertThat((String) JsonPath.read(view.body(), "$.health")).isEqualTo("OK");
    }

    @Test
    void sync_whenTheDeltaLinkExpired_410_listsEverythingAgain() throws Exception {
        STUB.file("A1", "politika-" + suffix + ".md", Stub.ROOT, "Birinci.");
        UUID collection = collection();
        UUID connector = create(collection);
        syncOk(connector);

        STUB.gone = true;
        STUB.file("B2", "notlar-" + suffix + ".txt", Stub.ROOT, "İkinci.");
        syncOk(connector);

        assertThat(lastRun(connector)).isEqualTo("SUCCEEDED/null/1/0/0/FULL/0");
        assertThat(fileNames(collection)).hasSize(2);
        assertThat(STUB.status410).isPositive();
    }

    @Test
    void sync_whenGraphThrottles_waitsForRetryAfterAndFinishes() throws Exception {
        STUB.file("A1", "politika-" + suffix + ".md", Stub.ROOT, "Birinci.");
        UUID connector = create(collection());
        STUB.throttle = 2;

        syncOk(connector);

        assertThat(STUB.status429).isEqualTo(2);
        assertThat(lastRun(connector)).isEqualTo("SUCCEEDED/null/1/0/0/FULL/0");
    }

    @Test
    void sync_whenTheDownloadRedirectsOutsideTheAllowlist_skipsTheFileWithoutFollowing() throws Exception {
        STUB.file("A1", "politika-" + suffix + ".md", Stub.ROOT, "Birinci.");
        STUB.redirectTo = "https://files.evil.example/download/A1";
        UUID connector = create(collection());

        syncOk(connector);

        assertThat(itemStates(connector)).containsExactly("drives/b!d1/items/A1=SKIPPED:REDIRECT_REFUSED");
        assertThat(STUB.requests).noneMatch(p -> p.startsWith("/download/"));
    }

    @Test
    void create_whenTheSiteOrTheFieldsDoNotFit_isRefused() throws Exception {
        UUID collection = collection();
        Map<String, Integer> cases = new LinkedHashMap<>();
        cases.put(body(collection).replace("https://contoso.sharepoint.com/sites/hr", "https://contoso.example.com/sites/hr"), 14013);
        cases.put(body(collection).replace("https://contoso.sharepoint.com/sites/hr", "http://contoso.sharepoint.com/sites/hr"), 14014);
        cases.put(body(collection).replace("https://contoso.sharepoint.com/sites/hr", "https://contoso.sharepoint.com/sites/../x"), 14014);
        cases.put(body(collection).replace("\"type\":\"SHAREPOINT\"", "\"type\":\"SHAREPOINT\",\"host\":\"10.1.2.3\""), 14012);
        cases.put(body(collection).replace("\"type\":\"SHAREPOINT\"", "\"type\":\"SHAREPOINT\",\"username\":\"svc\""), 14012);
        cases.put(body(collection).replace("\"type\":\"SHAREPOINT\"", "\"type\":\"SHAREPOINT\",\"pathPrefix\":\"../x\""), 14003);
        for (var c : cases.entrySet()) {
            HttpResponse<String> response = send(admin, "POST", "/v1/admin/connectors", c.getKey());
            assertThat(response.statusCode()).as(c.getKey()).isEqualTo(400);
            assertThat(response.body()).as(c.getKey()).contains("\"code\":" + c.getValue());
        }
        HttpResponse<String> noSecretField = send(admin, "POST", "/v1/admin/connectors",
                body(collection).replace("\"authMethod\":\"CLIENT_SECRET\"", "\"authMethod\":\"PASSWORD\""));
        assertThat(noSecretField.statusCode()).isEqualTo(400);
        assertThat(STUB.requests).as("validation asks nobody").isEmpty();
    }

    /** ADR-0019 for the SharePoint shape too: a user and an operator get 403, an anonymous caller 401. */
    @Test
    void endpoints_whenCallerIsNotAnAdmin_areDenied() throws Exception {
        UUID collection = collection();
        UUID connector = create(collection);
        String user = TestIdp.IDP.token().subject("acct-spo-user-" + suffix).build();
        String operator = TestIdp.IDP.token().subject("acct-spo-op-" + suffix).realmRoles("verso-operator", "verso-user")
                .build();
        List<String[]> calls = List.of(new String[]{"POST", "/v1/admin/connectors", body(collection)},
                new String[]{"PUT", "/v1/admin/connectors/" + connector, body(collection)},
                new String[]{"GET", "/v1/admin/connectors/" + connector, null},
                new String[]{"POST", "/v1/admin/connectors/" + connector + "/sync", null},
                new String[]{"GET", "/v1/admin/connectors/" + connector + "/runs", null},
                new String[]{"GET", "/v1/admin/connectors/" + connector + "/items", null},
                new String[]{"DELETE", "/v1/admin/connectors/" + connector, null});
        for (String[] call : calls) {
            assertThat(send(user, call[0], call[1], call[2]).statusCode()).as("user " + call[1]).isEqualTo(403);
            assertThat(send(operator, call[0], call[1], call[2]).statusCode()).as("operator " + call[1]).isEqualTo(403);
            assertThat(send(null, call[0], call[1], call[2]).statusCode()).as("anonymous " + call[1]).isEqualTo(401);
        }
        assertThat(STUB.requests).isEmpty();
    }

    private UUID create(UUID collection) throws Exception {
        HttpResponse<String> created = send(admin, "POST", "/v1/admin/connectors", body(collection));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.body()).contains("\"credentialRef\":\"SECRET_CONNECTOR_SHAREPOINT_SECRET\"");
        return UUID.fromString(JsonPath.read(created.body(), "$.id"));
    }

    private String body(UUID collection) {
        return "{\"name\":\"SP " + suffix + "\",\"type\":\"SHAREPOINT\",\"credentialRef\":"
                + "\"SECRET_CONNECTOR_SHAREPOINT_SECRET\",\"collectionId\":\"" + collection + "\",\"scheduleMinutes\":60,"
                + "\"maxFileBytes\":1000000,\"sharePoint\":{\"siteUrl\":\"https://contoso.sharepoint.com/sites/hr\","
                + "\"clientId\":\"" + CLIENT + "\",\"authMethod\":\"CLIENT_SECRET\"}}";
    }

    private UUID collection() throws Exception {
        HttpResponse<String> created = send(admin, "POST", "/v1/collections",
                "{\"name\":\"SharePoint alanı " + UUID.randomUUID().toString().substring(0, 8) + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.id"));
    }

    private void sync(UUID connector) throws Exception {
        assertThat(send(admin, "POST", "/v1/admin/connectors/" + connector + "/sync", null).statusCode()).isEqualTo(202);
        assertThat(worker.runDue()).isEqualTo(1);
    }

    private void syncOk(UUID connector) throws Exception {
        sync(connector);
        String failures = String.join(" | ", logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Connector sync failed")).toList());
        assertThat(lastRun(connector)).as("run against the stub at %s; %s", STUB.address(), failures)
                .doesNotStartWith("FAILED");
    }

    /** status/errorCode/added/updated/deleted/listingMode/pendingDeletions of the newest run. */
    private String lastRun(UUID connector) {
        return AuditTables.strings("select status || '/' || coalesce(error_code, 'null') || '/' || added || '/' || updated "
                + "|| '/' || deleted || '/' || listing_mode || '/' || coalesce(pending_deletions::text, 'null') "
                + "from connector.sync_run where connector_id = ? order by started_at desc limit 1", connector).getFirst();
    }

    private List<String> itemStates(UUID connector) {
        return AuditTables.strings("select path || '=' || state || coalesce(':' || failure_code, '') "
                + "from connector.connector_item where connector_id = ?", connector);
    }

    private List<String> fileNames(UUID collection) {
        return jdbc.queryForList("select file_name from document.document where collection_id = ?", String.class,
                collection);
    }

    private UUID document(UUID collection, String fileName) {
        return jdbc.queryForObject("select id from document.document where collection_id = ? and file_name = ?",
                UUID.class, collection, fileName);
    }

    private String content(UUID document) {
        return new String(jdbc.queryForObject("select content from document.document_file where document_id = ?",
                byte[].class, document), StandardCharsets.UTF_8);
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
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        responses.add(response);
        return response;
    }

    /**
     * Microsoft Graph and Entra's token endpoint in one stub: the site, its default library, driveItem/delta over a
     * change log (tokens are sequence numbers; 410 when {@code gone}), item permissions, the content endpoint's 302 to
     * a download URL on the stub itself (or {@code redirectTo}), and scripted 429s. Records requests and the
     * Authorization header of Graph and download requests separately.
     */
    static final class Stub {
        static final String ROOT = "ROOT0";
        static final String BEARER = "eyJ0eXAiOiJKV1QifQ.spo-" + UUID.randomUUID().toString().replace("-", "") + ".sig";
        private static final String SITE_ID = "contoso.sharepoint.com,0f0e0d0c-0b0a-0908-0706-050403020100,"
                + "00010203-0405-0607-0809-0a0b0c0d0e0f";
        private static final String DRIVE = "b!d1";

        static final class Item {
            final String id;
            final String name;
            final String parent;
            final boolean folder;
            volatile String content;
            volatile int version = 1;
            volatile boolean deleted;
            volatile boolean unique;

            Item(String id, String name, String parent, boolean folder, String content) {
                this.id = id;
                this.name = name;
                this.parent = parent;
                this.folder = folder;
                this.content = content;
            }
        }

        final HttpServer server;
        private final String host;
        final Map<String, Item> items = Collections.synchronizedMap(new LinkedHashMap<>());
        final List<String> log = new CopyOnWriteArrayList<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> graphAuthorizations = new CopyOnWriteArrayList<>();
        final List<String> downloadAuthorizations = new CopyOnWriteArrayList<>();
        final List<String> tokenForms = new CopyOnWriteArrayList<>();
        volatile boolean gone;
        volatile int throttle;
        volatile int status429;
        volatile int status410;
        volatile String redirectTo;

        Stub() {
            try {
                server = HttpServer.create(new InetSocketAddress(0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext("/", this::handle);
            server.start();
            host = privateAddress(server.getAddress().getPort());
        }

        String address() {
            return host + ":" + server.getAddress().getPort();
        }

        void reset() {
            items.clear();
            log.clear();
            requests.clear();
            graphAuthorizations.clear();
            downloadAuthorizations.clear();
            tokenForms.clear();
            gone = false;
            throttle = 0;
            status429 = 0;
            status410 = 0;
            redirectTo = null;
        }

        void folder(String id, String name, String parent) {
            items.put(id, new Item(id, name, parent, true, null));
            log.add(id);
        }

        void file(String id, String name, String parent, String content) {
            items.put(id, new Item(id, name, parent, false, content));
            log.add(id);
        }

        void change(String id, String content) {
            Item item = items.get(id);
            item.content = content;
            item.version++;
            log.add(id);
        }

        void delete(String id) {
            items.get(id).deleted = true;
            log.add(id);
        }

        void unique(String id) {
            items.get(id).unique = true;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
            requests.add(path + (query.isEmpty() ? "" : "?" + query));
            try (exchange) {
                if (exchange.getRequestMethod().equals("POST") && path.equals("/" + TENANT + "/oauth2/v2.0/token")) {
                    String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    tokenForms.add(form.replace(URLEncoderHolder.encode(SECRET), "<secret>"));
                    if (!form.contains("client_secret=" + URLEncoderHolder.encode(SECRET))) {
                        reply(exchange, 401, "{\"error\":\"invalid_client\"}");
                        return;
                    }
                    reply(exchange, 200, "{\"token_type\":\"Bearer\",\"expires_in\":3599,\"access_token\":\"" + BEARER + "\"}");
                    return;
                }
                if (path.startsWith("/download/")) {
                    downloadAuthorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                    Item item = items.get(path.substring("/download/".length()));
                    if (item == null) {
                        reply(exchange, 404, "{}");
                        return;
                    }
                    reply(exchange, 200, item.content);
                    return;
                }
                graphAuthorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                if (throttle > 0) {
                    throttle--;
                    status429++;
                    exchange.getResponseHeaders().add("Retry-After", "1");
                    exchange.sendResponseHeaders(429, -1);
                    return;
                }
                String p = path.substring("/v1.0".length());
                if (p.equals("/sites/contoso.sharepoint.com:/sites/hr")) {
                    reply(exchange, 200, "{\"id\":\"" + SITE_ID + "\"}");
                } else if (p.equals("/sites/" + SITE_ID + "/drive")) {
                    reply(exchange, 200, "{\"id\":\"" + DRIVE + "\",\"name\":\"Belgeler\"}");
                } else if (p.equals("/drives/" + DRIVE + "/root")) {
                    reply(exchange, 200, "{\"id\":\"" + ROOT + "\"}");
                } else if (p.equals("/drives/" + DRIVE + "/root/delta")) {
                    delta(exchange, query);
                } else if (p.startsWith("/drives/" + DRIVE + "/items/") && p.endsWith("/permissions")) {
                    Item item = items.get(between(p, "/permissions"));
                    if (item == null) {
                        reply(exchange, 404, "{}");
                        return;
                    }
                    String inherited = "{\"id\":\"p1\",\"roles\":[\"read\"],\"inheritedFrom\":{\"driveId\":\"" + DRIVE
                            + "\",\"id\":\"" + ROOT + "\"}}";
                    String own = "{\"id\":\"p2\",\"roles\":[\"read\"],\"grantedToV2\":{\"user\":{\"id\":\"u1\"}}}";
                    reply(exchange, 200, "{\"value\":[" + inherited + (item.unique ? "," + own : "") + "]}");
                } else if (p.startsWith("/drives/" + DRIVE + "/items/") && p.endsWith("/content")) {
                    String id = between(p, "/content");
                    exchange.getResponseHeaders().add("Location",
                            redirectTo != null ? redirectTo : "http://" + address() + "/download/" + id);
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    reply(exchange, 404, "{}");
                }
            }
        }

        private static String between(String p, String end) {
            return p.substring(("/drives/" + DRIVE + "/items/").length(), p.length() - end.length());
        }

        /** No token: everything alive (root first); token=N: the last state of what changed after change N. */
        private void delta(HttpExchange exchange, String query) throws IOException {
            Map<String, String> q = new HashMap<>();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) q.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
            List<String> ids = new ArrayList<>();
            if (q.containsKey("token")) {
                if (gone) {
                    status410++;
                    reply(exchange, 410, "{\"error\":{\"code\":\"resyncRequired\"}}");
                    return;
                }
                int since = Integer.parseInt(q.get("token"));
                for (int i = since; i < log.size(); i++) {
                    ids.remove(log.get(i));
                    ids.add(log.get(i));
                }
            } else {
                ids.add(ROOT);
                synchronized (items) {
                    for (Item item : items.values()) if (!item.deleted) ids.add(item.id);
                }
            }
            int skip = Integer.parseInt(q.getOrDefault("skip", "0"));
            int size = 2;
            List<String> slice = ids.subList(Math.min(skip, ids.size()), Math.min(skip + size, ids.size()));
            StringBuilder out = new StringBuilder("{\"value\":[");
            for (int i = 0; i < slice.size(); i++) {
                if (i > 0) out.append(',');
                out.append(itemJson(slice.get(i)));
            }
            out.append("],");
            String base = "http://" + address() + "/v1.0/drives/" + DRIVE + "/root/delta?";
            if (skip + size < ids.size()) {
                out.append("\"@odata.nextLink\":\"").append(base)
                        .append(q.containsKey("token") ? "token=" + q.get("token") + "&" : "").append("skip=")
                        .append(skip + size).append("\"}");
            } else {
                out.append("\"@odata.deltaLink\":\"").append(base).append("token=").append(log.size()).append("\"}");
            }
            reply(exchange, 200, out.toString());
        }

        private String itemJson(String id) {
            if (id.equals(ROOT)) return "{\"id\":\"" + ROOT + "\",\"name\":\"root\",\"root\":{},\"folder\":{}}";
            Item item = items.get(id);
            if (item.deleted) return "{\"id\":\"" + id + "\",\"deleted\":{\"state\":\"deleted\"}}";
            String common = "\"id\":\"" + id + "\",\"name\":" + json(item.name) + ",\"parentReference\":{\"driveId\":\""
                    + DRIVE + "\",\"id\":\"" + item.parent + "\"},\"lastModifiedDateTime\":\""
                    + Instant.parse("2026-10-20T10:00:00Z").plusSeconds(item.version * 60L) + "\",\"eTag\":\"e"
                    + item.version + "\",\"cTag\":\"c" + id + "." + item.version + "\"";
            if (item.folder) return "{" + common + ",\"folder\":{\"childCount\":1}}";
            return "{" + common + ",\"size\":" + item.content.getBytes(StandardCharsets.UTF_8).length
                    + ",\"file\":{\"mimeType\":\"text/plain\"}}";
        }

        private static void reply(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        private static String json(String value) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : value.toCharArray()) {
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                else out.append(c);
            }
            return out.append('"').toString();
        }

        /** A private IPv4 address of this host on which the stub answers (loopback is refused by the connector). */
        private static String privateAddress(int port) {
            try {
                for (java.net.NetworkInterface nic : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                    if (!nic.isUp() || nic.isLoopback()) continue;
                    for (java.net.InetAddress address : Collections.list(nic.getInetAddresses())) {
                        if (!(address instanceof java.net.Inet4Address) || !address.isSiteLocalAddress()) continue;
                        try (java.net.Socket socket = new java.net.Socket()) {
                            socket.connect(new InetSocketAddress(address, port), 1_000);
                            return address.getHostAddress();
                        } catch (IOException e) {
                            // not this one
                        }
                    }
                }
            } catch (java.net.SocketException e) {
                throw new IllegalStateException(e);
            }
            throw new IllegalStateException("no private host address reaches the stub");
        }
    }

    /** Form encoding as the connector writes it. */
    private static final class URLEncoderHolder {
        static String encode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}
