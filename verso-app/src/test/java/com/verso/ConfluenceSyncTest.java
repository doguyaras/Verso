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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The Confluence connector end to end against a stub Confluence REST server (ADR-0044); no Atlassian account. The stub
 * runs in this JVM and is reached on a private (RFC 1918) address of this host, inside the Data Center allowlist, the way
 * an installation reaches an internal Confluence (loopback is refused by design). It answers the CQL search (honouring
 * the relative "lastmodified" window), the restrictions API and the page bodies in storage format.
 *
 * <p>Covered: add, update and delete reach the collection (delete only at a full listing); a restricted page is skipped;
 * a page restricted later without a new version is removed by the full listing's restriction sweep; a Cloud site is
 * refused while external egress is off (API and sync, the stub is never asked); a redirect to another host stops the
 * run; the token appears in no log line and no response; only verso-admin reaches the endpoints; the runs carry
 * listingMode and pendingDeletions. http:// is allowed for the stub only (require-https=false).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.connector.sync-enabled=false",
                "verso.connector.allowed-networks=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16",
                "verso.connector.confluence.require-https=false", "verso.connector.confluence.max-attempts=2",
                "verso.connector.confluence.retry-base=10ms", "verso.connector.confluence.max-retry-wait=1s"})
@VersoTestEnvironment
class ConfluenceSyncTest {

    private static final String TOKEN = "pat-" + HexFormat.of().formatHex(new SecureRandom().generateSeed(16));
    private static final Stub STUB = new Stub();

    @DynamicPropertySource
    static void confluence(DynamicPropertyRegistry registry) {
        registry.add("SECRET_CONNECTOR_CONFLUENCE_TOKEN", () -> TOKEN);
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
    private final String adminSub = "acct-cfl-admin-" + suffix;
    private final String admin = TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    private final List<HttpResponse<String>> responses = new ArrayList<>();
    private Logger root;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        AuditTables.update("DELETE FROM connector.connector");
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection WHERE name LIKE 'Wiki alanı %'");
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
        jdbc.update("DELETE FROM document.collection WHERE name LIKE 'Wiki alanı %'");
        root.detachAppender(logs);
        logs.stop();
        String all = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).as("the token reaches no log line").doesNotContain(TOKEN);
        assertThat(all).as("no page title or body in the log").doesNotContain("Gizli-" + suffix)
                .doesNotContain("Genel-" + suffix).doesNotContain("maaş tablosu");
        for (HttpResponse<String> response : responses) {
            assertThat(response.body()).as("nor a response").doesNotContain(TOKEN);
        }
        assertThat(STUB.authorizations).as("every request carried the token as Bearer")
                .allMatch(("Bearer " + TOKEN)::equals);
    }

    @Test
    void sync_whenPagesAreAddedChangedRestrictedAndDeleted_mirrorsThemIntoTheCollection() throws Exception {
        Instant old = Instant.now().minus(Duration.ofDays(2));
        STUB.page(101, "Genel-" + suffix, 1, old, "<h1>Başlık</h1><p>İzin 14 gündür.</p>"
                + "<table><tr><th>Tür</th><th>Gün</th></tr><tr><td>Yıllık</td><td>14</td></tr></table>", false);
        STUB.page(102, "Gizli-" + suffix, 1, old, "<p>maaş tablosu</p>", true);
        STUB.page(103, "Ek-" + suffix, 1, old, "<p>ek sayfa</p>", false);
        UUID collection = collection();
        UUID connector = dataCenter(collection);

        syncOk(connector);

        assertThat(fileNames(collection)).containsExactlyInAnyOrder("Genel-" + suffix + ".md", "Ek-" + suffix + ".md");
        assertThat(itemStates(connector)).containsExactlyInAnyOrder("pages/101=SYNCED", "pages/102=SKIPPED:RESTRICTED",
                "pages/103=SYNCED");
        assertThat(content(document(collection, "Genel-" + suffix + ".md"))).isEqualTo("# Genel-" + suffix
                + "\n\n# Başlık\n\nİzin 14 gündür.\n\n| Tür | Gün |\n| --- | --- |\n| Yıllık | 14 |");
        assertThat(lastRun(connector)).isEqualTo("SUCCEEDED/null/2/0/0/FULL/0");
        assertThat(STUB.requests).as("the restricted page's body is never fetched").noneMatch(p -> p.equals("/rest/api/content/102"));

        STUB.page(101, "Genel-" + suffix, 2, Instant.now(), "<p>İzin 20 gündür.</p>", false);
        syncOk(connector);

        assertThat(content(document(collection, "Genel-" + suffix + ".md"))).contains("İzin 20 gündür.");
        assertThat(lastRun(connector)).as("incremental: only the changed page").isEqualTo("SUCCEEDED/null/0/1/0/INCREMENTAL/0");
        assertThat(STUB.lastWindowMinutes).as("CQL asked for a window, not everything").isNotNull();

        // The restriction sweep: a restriction does not change the version or "last modified" (CONFCLOUD-41375), so
        // only the full listing sees it.
        STUB.restrict(103);
        syncOk(connector);
        assertThat(fileNames(collection)).as("an incremental run does not see it").contains("Ek-" + suffix + ".md");
        fullListingDue(connector);
        syncOk(connector);
        assertThat(fileNames(collection)).containsExactly("Genel-" + suffix + ".md");
        assertThat(itemStates(connector)).contains("pages/103=SKIPPED:RESTRICTED");
        assertThat(lastRun(connector)).isEqualTo("SUCCEEDED/null/0/0/0/FULL/0");

        UUID genel = document(collection, "Genel-" + suffix + ".md");
        STUB.remove(101);
        syncOk(connector);
        assertThat(fileNames(collection)).as("incremental runs never delete").hasSize(1);
        fullListingDue(connector);
        syncOk(connector);
        assertThat(fileNames(collection)).isEmpty();
        for (String table : List.of("document", "document_file", "document_principal", "document_chunk")) {
            String column = table.equals("document") ? "id" : "document_id";
            assertThat(jdbc.queryForObject("select count(*) from document." + table + " where " + column + " = ?",
                    Long.class, genel)).as("ADR-0034: nothing of the deleted page stays in " + table).isZero();
        }
        assertThat(lastRun(connector)).isEqualTo("SUCCEEDED/null/0/0/1/FULL/0");

        HttpResponse<String> runs = send(admin, "GET", "/v1/admin/connectors/" + connector + "/runs?size=1", null);
        assertThat((String) JsonPath.read(runs.body(), "$.data[0].listingMode")).isEqualTo("FULL");
        assertThat((Integer) JsonPath.read(runs.body(), "$.data[0].pendingDeletions")).isZero();
        HttpResponse<String> view = send(admin, "GET", "/v1/admin/connectors/" + connector, null);
        assertThat((String) JsonPath.read(view.body(), "$.type")).isEqualTo("CONFLUENCE");
        assertThat((String) JsonPath.read(view.body(), "$.confluence.deployment")).isEqualTo("DATA_CENTER");
        assertThat((String) JsonPath.read(view.body(), "$.health")).isEqualTo("OK");
        assertThat(AuditTables.awaitCount(1, "select count(*) from audit.event where action = 'CONNECTOR_DOCUMENT_DELETED' "
                + "and detail_code = 'SOURCE_RESTRICTED'")).isEqualTo(1);
    }

    @Test
    void cloud_whenExternalEgressIsOff_isRefusedByTheApiAndBySync_withoutAnyRequest() throws Exception {
        UUID collection = collection();
        HttpResponse<String> refused = send(admin, "POST", "/v1/admin/connectors", cloudBody(collection));
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("\"code\":14013");

        // A row that reached the database another way (an older version, a restore) is refused at sync time too.
        UUID connector = dataCenter(collection);
        AuditTables.update("UPDATE connector.connector SET deployment = 'CLOUD', username = 'svc@acme.example', "
                + "base_url = 'https://acme.atlassian.net/wiki', host = 'acme.atlassian.net', port = 443 WHERE id = ?",
                connector);
        sync(connector);

        assertThat(lastRun(connector)).startsWith("FAILED/EGRESS_DENIED/");
        assertThat(STUB.requests).as("nothing left the host").isEmpty();
    }

    @Test
    void sync_whenTheSiteRedirectsToAnotherHost_stopsWithoutFollowing() throws Exception {
        STUB.redirectTo = "http://confluence-mirror.invalid/rest/api/content/search";
        UUID connector = dataCenter(collection());

        sync(connector);

        assertThat(lastRun(connector)).startsWith("FAILED/REDIRECT_REFUSED/");
        assertThat(STUB.requests).hasSize(1);
    }

    @Test
    void create_whenTheTypeAndItsFieldsDoNotFit_isRefused() throws Exception {
        UUID collection = collection();
        String smbWithoutHost = ConnectorApiTest.body("Tip-" + suffix, collection, "").replace("\"host\":\"10.20.30.40\",", "");
        String confluenceWithShare = dataCenterBody(collection).replace("\"type\":\"CONFLUENCE\"",
                "\"type\":\"CONFLUENCE\",\"share\":\"fs01\"");
        String dataCenterWithUser = dataCenterBody(collection).replace("\"type\":\"CONFLUENCE\"",
                "\"type\":\"CONFLUENCE\",\"username\":\"svc@acme.example\"");
        String plainHttpUserInfo = dataCenterBody(collection).replace("http://", "http://user:pw@");
        String publicLiteral = dataCenterBody(collection).replace(STUB.address(), "8.8.8.8:" + STUB.server.getAddress().getPort());
        for (var c : Map.of(smbWithoutHost, 14012, confluenceWithShare, 14012, dataCenterWithUser, 14012,
                plainHttpUserInfo, 14014, publicLiteral, 14004).entrySet()) {
            HttpResponse<String> response = send(admin, "POST", "/v1/admin/connectors", c.getKey());
            assertThat(response.statusCode()).as(c.getKey()).isEqualTo(400);
            assertThat(response.body()).as(c.getKey()).contains("\"code\":" + c.getValue());
        }
        UUID connector = dataCenter(collection);
        HttpResponse<String> retype = send(admin, "PUT", "/v1/admin/connectors/" + connector,
                ConnectorApiTest.body("Tip2-" + suffix, collection, ""));
        assertThat(retype.statusCode()).as(retype.body()).isEqualTo(409);
        assertThat(retype.body()).contains("\"code\":14015");
    }

    /** ADR-0019 for the Confluence shape too: a user and an operator get 403, an anonymous caller 401. */
    @Test
    void endpoints_whenCallerIsNotAnAdmin_areDenied() throws Exception {
        UUID collection = collection();
        UUID connector = dataCenter(collection);
        String user = TestIdp.IDP.token().subject("acct-cfl-user-" + suffix).build();
        String operator = TestIdp.IDP.token().subject("acct-cfl-op-" + suffix).realmRoles("verso-operator", "verso-user")
                .build();
        List<String[]> calls = List.of(new String[]{"POST", "/v1/admin/connectors", dataCenterBody(collection)},
                new String[]{"PUT", "/v1/admin/connectors/" + connector, dataCenterBody(collection)},
                new String[]{"GET", "/v1/admin/connectors/" + connector, null},
                new String[]{"POST", "/v1/admin/connectors/" + connector + "/sync", null},
                new String[]{"GET", "/v1/admin/connectors/" + connector + "/runs", null},
                new String[]{"GET", "/v1/admin/connectors/" + connector + "/items", null});
        for (String[] call : calls) {
            assertThat(send(user, call[0], call[1], call[2]).statusCode()).as("user " + call[1]).isEqualTo(403);
            assertThat(send(operator, call[0], call[1], call[2]).statusCode()).as("operator " + call[1]).isEqualTo(403);
            assertThat(send(null, call[0], call[1], call[2]).statusCode()).as("anonymous " + call[1]).isEqualTo(401);
        }
        assertThat(STUB.requests).isEmpty();
    }

    private UUID dataCenter(UUID collection) throws Exception {
        HttpResponse<String> created = send(admin, "POST", "/v1/admin/connectors", dataCenterBody(collection));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.body()).doesNotContain("password").contains("\"credentialRef\":\"SECRET_CONNECTOR_CONFLUENCE_TOKEN\"");
        return UUID.fromString(JsonPath.read(created.body(), "$.id"));
    }

    private String dataCenterBody(UUID collection) {
        return "{\"name\":\"Wiki " + suffix + "\",\"type\":\"CONFLUENCE\",\"credentialRef\":"
                + "\"SECRET_CONNECTOR_CONFLUENCE_TOKEN\",\"collectionId\":\"" + collection + "\",\"scheduleMinutes\":60,"
                + "\"maxFileBytes\":1000000,\"confluence\":{\"baseUrl\":\"http://" + STUB.address() + "\","
                + "\"deployment\":\"DATA_CENTER\",\"spaceKeys\":[\"HR\"],\"pageSize\":2}}";
    }

    private String cloudBody(UUID collection) {
        return "{\"name\":\"Bulut " + suffix + "\",\"type\":\"CONFLUENCE\",\"username\":\"svc@acme.example\","
                + "\"credentialRef\":\"SECRET_CONNECTOR_CONFLUENCE_TOKEN\",\"collectionId\":\"" + collection + "\","
                + "\"scheduleMinutes\":60,\"confluence\":{\"baseUrl\":\"https://acme.atlassian.net/wiki\","
                + "\"deployment\":\"CLOUD\",\"spaceKeys\":[\"HR\"]}}";
    }

    private UUID collection() throws Exception {
        HttpResponse<String> created = send(admin, "POST", "/v1/collections",
                "{\"name\":\"Wiki alanı " + UUID.randomUUID().toString().substring(0, 8) + "\"}");
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

    /** The next run lists everything (the interval has passed). */
    private static void fullListingDue(UUID connector) {
        AuditTables.update("UPDATE connector.connector SET full_listing_at = now() - interval '2 days' WHERE id = ?",
                connector);
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

    /** A Confluence REST stub: CQL search (with the relative window), restrictions, page bodies; records requests. */
    static final class Stub {
        private static final Pattern WINDOW = Pattern.compile("lastmodified >= now\\(\"-(\\d+)m\"\\)");
        private static final Pattern RESTRICTION = Pattern.compile("/rest/api/content/(\\d+)/restriction/byOperation/read");
        private static final Pattern CONTENT = Pattern.compile("/rest/api/content/(\\d+)");

        record Page(long id, String title, int version, Instant when, String body, boolean restricted) {
        }

        final HttpServer server;
        private final String host;
        final Map<Long, Page> pages = new ConcurrentHashMap<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final List<String> authorizations = new CopyOnWriteArrayList<>();
        volatile String redirectTo;
        volatile Long lastWindowMinutes;

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
            pages.clear();
            requests.clear();
            authorizations.clear();
            redirectTo = null;
            lastWindowMinutes = null;
        }

        void page(long id, String title, int version, Instant when, String body, boolean restricted) {
            pages.put(id, new Page(id, title, version, when, body, restricted));
        }

        void restrict(long id) {
            pages.computeIfPresent(id, (k, p) -> new Page(p.id(), p.title(), p.version(), p.when(), p.body(), true));
        }

        void remove(long id) {
            pages.remove(id);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getRawPath();
            requests.add(path);
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            try (exchange) {
                if (redirectTo != null) {
                    exchange.getResponseHeaders().add("Location", redirectTo);
                    exchange.sendResponseHeaders(302, -1);
                    return;
                }
                if (path.equals("/rest/api/content/search")) {
                    reply(exchange, 200, search(exchange.getRequestURI().getRawQuery()));
                    return;
                }
                Matcher restriction = RESTRICTION.matcher(path);
                if (restriction.matches()) {
                    Page page = pages.get(Long.parseLong(restriction.group(1)));
                    if (page == null) {
                        reply(exchange, 404, "{}");
                        return;
                    }
                    String user = page.restricted() ? "[{\"type\":\"known\",\"accountId\":\"x\"}]" : "[]";
                    reply(exchange, 200, "{\"operation\":\"read\",\"restrictions\":{\"user\":{\"results\":" + user
                            + ",\"size\":" + (page.restricted() ? 1 : 0) + "},\"group\":{\"results\":[],\"size\":0}}}");
                    return;
                }
                Matcher content = CONTENT.matcher(path);
                if (content.matches()) {
                    Page page = pages.get(Long.parseLong(content.group(1)));
                    if (page == null) {
                        reply(exchange, 404, "{}");
                        return;
                    }
                    reply(exchange, 200, "{\"id\":\"" + page.id() + "\",\"title\":" + json(page.title())
                            + ",\"body\":{\"storage\":{\"value\":" + json(page.body()) + "}}}");
                    return;
                }
                reply(exchange, 404, "{}");
            }
        }

        /** Every page in id order, filtered by the relative window when the CQL has one; one page of two. */
        private String search(String rawQuery) {
            Map<String, String> query = new TreeMap<>();
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                query.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
            Matcher window = WINDOW.matcher(query.getOrDefault("cql", ""));
            Instant since = null;
            if (window.find()) {
                lastWindowMinutes = Long.parseLong(window.group(1));
                since = Instant.now().minus(Duration.ofMinutes(lastWindowMinutes));
            }
            List<Page> matching = new ArrayList<>();
            for (Page page : pages.values()) if (since == null || !page.when().isBefore(since)) matching.add(page);
            matching.sort((a, b) -> Long.compare(a.id(), b.id()));
            int start = Integer.parseInt(query.getOrDefault("start", "0"));
            int limit = Integer.parseInt(query.getOrDefault("limit", "25"));
            List<Page> slice = matching.subList(Math.min(start, matching.size()), Math.min(start + limit, matching.size()));
            StringBuilder out = new StringBuilder("{\"results\":[");
            for (int i = 0; i < slice.size(); i++) {
                Page page = slice.get(i);
                if (i > 0) out.append(',');
                out.append("{\"id\":\"").append(page.id()).append("\",\"type\":\"page\",\"title\":").append(json(page.title()))
                        .append(",\"version\":{\"number\":").append(page.version()).append(",\"when\":\"")
                        .append(OffsetDateTime.ofInstant(page.when(), ZoneOffset.ofHours(3))).append("\"},\"ancestors\":[]}");
            }
            out.append("],\"_links\":{");
            if (start + limit < matching.size()) {
                String next = "/rest/api/content/search?cql=" + encode(query.get("cql")) + "&limit=" + limit
                        + "&start=" + (start + limit) + "&expand=" + encode(query.getOrDefault("expand", ""));
                out.append("\"next\":").append(json(next));
            }
            return out.append("}}").toString();
        }

        private static String encode(String value) {
            return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
}
