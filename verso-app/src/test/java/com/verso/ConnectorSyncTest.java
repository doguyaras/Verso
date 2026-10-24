package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.connector.worker.ConnectorSyncWorker;
import com.verso.support.AuditTables;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The SMB connector end to end against a real Samba server (ADR-0038): adds, changes and deletes reach the collection
 * with the complete derived-data deletion, a re-run changes nothing, a crash between "document stored" and "item
 * recorded" resumes without a duplicate, a held lease keeps a second run out, exclude globs and the size limit hold,
 * a host outside the egress allowlist is never contacted, a missing folder deletes nothing, a deleted connector takes
 * its documents with it, and the share's password appears in no log line and no response.
 *
 * <p>The Samba image is pinned by digest (reference 18.1). The application reaches the container on its bridge address
 * (port 445), which is inside the private allowlist; loopback is refused by design, so the published port is not used.
 * The share is read-only for the connector's account (RW=false); the test writes files as root inside the container.
 * The ingestion worker is off in tests: documents stay PENDING, the pipeline itself is covered by its own tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.connector.sync-enabled=false",
                "verso.connector.allowed-networks=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"})
@VersoTestEnvironment
class ConnectorSyncTest {

    static final String SAMBA_IMAGE =
            "dockurr/samba:4.23.10@sha256:2a0452a12b7a244a22f18d0f233101bb8d4952b5500ae263905e3b9dad3bf2ae";
    private static final String USER = "verso";
    private static String sambaHost;
    private static final String PASSWORD = HexFormat.of().formatHex(new SecureRandom().generateSeed(12));
    @SuppressWarnings("resource")
    private static final GenericContainer<?> SAMBA = new GenericContainer<>(DockerImageName.parse(SAMBA_IMAGE))
            .withEnv("NAME", "Data").withEnv("USER", USER).withEnv("PASS", PASSWORD).withEnv("RW", "false")
            .withExposedPorts(445)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void samba(DynamicPropertyRegistry registry) {
        SAMBA.start();
        registry.add("SECRET_CONNECTOR_SMB_PASSWORD", () -> PASSWORD);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    ConnectorSyncWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String admin = TestIdp.IDP.token().subject("acct-sync-admin-" + suffix).realmRoles("verso-admin").build();
    private final List<HttpResponse<String>> responses = new ArrayList<>();
    private Logger root;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        AuditTables.update("DELETE FROM connector.connector");
        AuditTables.reset();
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection");
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        AuditTables.update("DELETE FROM connector.connector");
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection");
        root.detachAppender(logs);
        logs.stop();
        String all = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(all).as("the share's password reaches no log line").doesNotContain(PASSWORD);
        // Folder names carry this suffix; neither our code nor the SMB library may log a path (llm-rules 2.1).
        assertThat(all).as("no path or file name in the log").doesNotContain(suffix).doesNotContain("\\Data");
        for (HttpResponse<String> response : responses) {
            assertThat(response.body()).as("nor a response").doesNotContain(PASSWORD);
        }
    }

    @Test
    void sync_whenFilesAreAddedChangedAndDeleted_mirrorsThemIntoTheCollection() throws Exception {
        String root = "t1-" + suffix;
        write(root + "/a.txt", "alpha version one");
        write(root + "/alt/b.md", "# beta");
        write(root + "/tablo.xlsx", "not a document type");
        write(root + "/arsiv/eski.txt", "excluded by a glob");
        write(root + "/buyuk.txt", "x".repeat(2_000));
        UUID collection = collection("/hukuk");
        UUID connector = connector(collection, root, "[\"arsiv/**\"]", 1_000);

        syncOk(connector);

        assertThat(fileNames(collection)).containsExactlyInAnyOrder("a.txt", "b.md");
        assertThat(lastRun(connector)).isEqualTo("SUCCEEDED/null/2/0/0");
        assertThat(itemStates(connector)).containsExactlyInAnyOrder("a.txt=SYNCED", "alt/b.md=SYNCED",
                "buyuk.txt=SKIPPED:TOO_LARGE");
        UUID a = document(collection, "a.txt");
        assertThat(content(a)).isEqualTo("alpha version one");
        assertThat(jdbc.queryForList("select principal from document.document_principal where document_id = ?",
                String.class, a)).as("the collection's grants decide who reads it (ADR-0028)").containsExactly("group:/hukuk");
        assertThat(jdbc.queryForObject("select account_id from document.document where id = ?", String.class, a))
                .isEqualTo("connector:" + connector);

        write(root + "/a.txt", "alpha version two, longer");
        syncOk(connector);

        UUID a2 = document(collection, "a.txt");
        assertThat(a2).isNotEqualTo(a);
        assertThat(content(a2)).isEqualTo("alpha version two, longer");
        assertThat(count("select count(*) from document.document where id = ?", a)).as("the old version is gone").isZero();
        assertThat(lastRun(connector)).isEqualTo("SUCCEEDED/null/0/1/0");

        delete(root + "/alt/b.md");
        UUID b = document(collection, "b.md");
        syncOk(connector);

        assertThat(fileNames(collection)).containsExactly("a.txt");
        assertThat(lastRun(connector)).isEqualTo("SUCCEEDED/null/0/0/1");
        for (String table : List.of("document", "document_file", "document_principal", "document_page", "document_chunk")) {
            String column = table.equals("document") ? "id" : "document_id";
            assertThat(count("select count(*) from document." + table + " where " + column + " = ?", b))
                    .as("ADR-0034: nothing of the deleted file stays in " + table).isZero();
        }

        syncOk(connector);
        assertThat(lastRun(connector)).as("a re-run without changes changes nothing").isEqualTo("SUCCEEDED/null/0/0/0");
        assertThat(document(collection, "a.txt")).isEqualTo(a2);

        assertThat(AuditTables.awaitCount(3, "select count(*) from audit.event where actor_sub = 'system:connector-sync' "
                + "and action = 'CONNECTOR_DOCUMENT_ADDED' and actor_roles = '{}'")).isEqualTo(3);
        assertThat(AuditTables.awaitCount(2, "select count(*) from audit.event where actor_sub = 'system:connector-sync' "
                + "and action = 'CONNECTOR_DOCUMENT_DELETED'")).isEqualTo(2);
        assertThat(AuditTables.awaitCount(4, "select count(*) from audit.event where action = 'CONNECTOR_SYNC_FINISHED' "
                + "and target_id = ?", connector)).isEqualTo(4);
        HttpResponse<String> view = send("GET", "/v1/admin/connectors/" + connector, null);
        assertThat((String) JsonPath.read(view.body(), "$.health")).isEqualTo("OK");
        HttpResponse<String> skipped = send("GET", "/v1/admin/connectors/" + connector + "/items?state=SKIPPED", null);
        assertThat((List<String>) JsonPath.read(skipped.body(), "$.data[*].path")).containsExactly("buyuk.txt");
    }

    /**
     * A crash after the document module stored a file and before the item was recorded: the next run (on any
     * instance, once the lease expired) finds the stored document by its key instead of adding a copy, and closes the
     * dead run. While the lease is still held, nobody else runs the connector.
     */
    @Test
    void sync_whenTheLastRunDiedMidway_resumesWithoutDuplicates_andALiveLeaseKeepsOthersOut() throws Exception {
        String root = "t2-" + suffix;
        write(root + "/one.txt", "first file");
        write(root + "/two.txt", "second file");
        UUID collection = collection(null);
        UUID connector = connector(collection, root, "[]", 1_000_000);
        syncOk(connector);
        UUID one = document(collection, "one.txt");

        AuditTables.update("DELETE FROM connector.connector_item WHERE connector_id = ? AND path = 'one.txt'", connector);
        AuditTables.update("INSERT INTO connector.sync_run (connector_id, status) VALUES (?, 'RUNNING')", connector);
        AuditTables.update("UPDATE connector.connector SET claim_token = gen_random_uuid(), next_run_at = now(), "
                + "locked_until = now() + interval '5 minutes' WHERE id = ?", connector);
        assertThat(worker.runDue()).as("the lease is held by the (dead) instance").isZero();

        AuditTables.update("UPDATE connector.connector SET locked_until = now() - interval '1 second' WHERE id = ?", connector);
        assertThat(worker.runDue()).isEqualTo(1);

        assertThat(fileNames(collection)).containsExactlyInAnyOrder("one.txt", "two.txt");
        assertThat(document(collection, "one.txt")).as("REPLAYED, not stored twice").isEqualTo(one);
        assertThat(itemStates(connector)).containsExactlyInAnyOrder("one.txt=SYNCED", "two.txt=SYNCED");
        assertThat(AuditTables.strings("select status || '/' || coalesce(error_code, '-') from connector.sync_run "
                + "where connector_id = ? order by started_at", connector))
                .containsExactly("SUCCEEDED/-", "ABANDONED/INSTANCE_STOPPED", "SUCCEEDED/-");
    }

    @Test
    void sync_whenTheHostResolvesOutsideTheAllowlist_neverConnects_andFails() throws Exception {
        UUID collection = collection(null);
        String json = ConnectorApiTest.body("Dışarı " + suffix, collection, "")
                .replace("\"10.20.30.40\"", "\"localhost\"");
        UUID connector = UUID.fromString(JsonPath.read(send("POST", "/v1/admin/connectors", json).body(), "$.id"));

        sync(connector);

        assertThat(lastRun(connector)).startsWith("FAILED/EGRESS_DENIED");
        assertThat(AuditTables.strings("select last_error_code || '/' || consecutive_failures from connector.connector "
                + "where id = ?", connector)).containsExactly("EGRESS_DENIED/1");
        assertThat(fileNames(collection)).isEmpty();
        assertThat((String) JsonPath.read(send("GET", "/v1/admin/connectors/" + connector, null).body(), "$.health"))
                .isEqualTo("FAILING");
    }

    /** A folder that vanished (renamed, permission lost) must not read as "every file was deleted". */
    @Test
    void sync_whenThePrefixFolderIsGone_deletesNothing() throws Exception {
        String root = "t4-" + suffix;
        write(root + "/kalir.txt", "stays");
        UUID collection = collection(null);
        UUID connector = connector(collection, root, "[]", 1_000_000);
        syncOk(connector);

        exec("rm -rf \"$D/" + root + "\"");
        sync(connector);

        assertThat(lastRun(connector)).startsWith("FAILED/PATH_NOT_FOUND");
        assertThat(fileNames(collection)).containsExactly("kalir.txt");
    }

    @Test
    void delete_whenTheConnectorIsRemoved_takesItsDocumentsWithIt() throws Exception {
        String root = "t5-" + suffix;
        write(root + "/x.txt", "x");
        write(root + "/y.md", "# y");
        UUID collection = collection(null);
        UUID connector = connector(collection, root, "[]", 1_000_000);
        syncOk(connector);
        assertThat(fileNames(collection)).hasSize(2);

        assertThat(send("DELETE", "/v1/admin/connectors/" + connector, null).statusCode()).isEqualTo(202);
        assertThat(worker.runDue()).isEqualTo(1);

        assertThat(fileNames(collection)).isEmpty();
        assertThat(send("GET", "/v1/admin/connectors/" + connector, null).statusCode()).isEqualTo(404);
        assertThat(AuditTables.count("select count(*) from connector.connector_item where connector_id = ?", connector))
                .isZero();
        assertThat(AuditTables.awaitCount(2, "select count(*) from audit.event where action = 'CONNECTOR_DOCUMENT_DELETED' "
                + "and detail_code = 'CONNECTOR_DELETED'")).isEqualTo(2);
    }

    private void sync(UUID connector) throws Exception {
        assertThat(send("POST", "/v1/admin/connectors/" + connector + "/sync", null).statusCode()).isEqualTo(202);
        assertThat(worker.runDue()).isEqualTo(1);
    }

    /** A sync that is expected to reach the share; on failure the message carries the run's fixed code. */
    private void syncOk(UUID connector) throws Exception {
        sync(connector);
        String failures = String.join(" | ", logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Connector sync failed")).toList());
        assertThat(lastRun(connector)).as("run against the Samba share at %s; %s", sambaAddress(), failures)
                .doesNotStartWith("FAILED");
    }

    private UUID connector(UUID collection, String prefix, String excludes, long maxFileBytes) throws Exception {
        String json = ConnectorApiTest.body("Paylaşım " + prefix, collection, prefix)
                .replace("\"10.20.30.40\"", "\"" + sambaAddress() + "\"").replace("\"fs01\"", "\"Data\"")
                .replace("\"svc-verso\"", "\"" + USER + "\"").replace("\"excludeGlobs\":[]", "\"excludeGlobs\":" + excludes)
                .replace("\"maxFileBytes\":1000000", "\"maxFileBytes\":" + maxFileBytes)
                .replace("\"scheduleMinutes\":60", "\"scheduleMinutes\":60,\"port\":" + SAMBA.getMappedPort(445));
        HttpResponse<String> created = send("POST", "/v1/admin/connectors", json);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.id"));
    }

    private UUID collection(String group) throws Exception {
        HttpResponse<String> created = send("POST", "/v1/collections",
                "{\"name\":\"Paylaşım alanı " + UUID.randomUUID().toString().substring(0, 8) + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID id = UUID.fromString(JsonPath.read(created.body(), "$.id"));
        if (group != null) {
            HttpResponse<String> grant = send("POST", "/v1/collections/" + id + "/grants",
                    "{\"principalType\":\"GROUP\",\"principal\":\"" + group + "\"}");
            assertThat(grant.statusCode()).as(grant.body()).isEqualTo(201);
        }
        return id;
    }

    /** status/errorCode/added/updated/deleted of the newest run. */
    private String lastRun(UUID connector) {
        return AuditTables.strings("select status || '/' || coalesce(error_code, 'null') || '/' || added || '/' || updated "
                + "|| '/' || deleted from connector.sync_run where connector_id = ? order by started_at desc limit 1",
                connector).getFirst();
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

    private long count(String sql, Object... params) {
        return jdbc.queryForObject(sql, Long.class, params);
    }

    /**
     * A private (RFC 1918) address of this host on which the published SMB port answers. Loopback is refused by design
     * and a container's bridge address is not routable on every Docker host (Docker Desktop), so the test reaches the
     * share the way an installation reaches a file server: a private LAN address, inside the allowlist.
     */
    private static synchronized String sambaAddress() {
        if (sambaHost != null) return sambaHost;
        int port = SAMBA.getMappedPort(445);
        try {
            for (java.net.NetworkInterface nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (java.net.InetAddress address : java.util.Collections.list(nic.getInetAddresses())) {
                    if (!(address instanceof java.net.Inet4Address) || !address.isSiteLocalAddress()) continue;
                    try (java.net.Socket socket = new java.net.Socket()) {
                        socket.connect(new java.net.InetSocketAddress(address, port), 1_000);
                        sambaHost = address.getHostAddress();
                        return sambaHost;
                    } catch (java.io.IOException e) {
                        // not this one
                    }
                }
            }
        } catch (java.net.SocketException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("no private host address reaches the published SMB port");
    }

    /** Writes a file into the share as root (the connector's account has read access only). */
    private static void write(String path, String content) throws Exception {
        String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : "";
        exec("mkdir -p \"$D/" + dir + "\" && printf '%s' '" + content.replace("'", "'\\''") + "' > \"$D/" + path
                + "\" && chmod 0644 \"$D/" + path + "\"");
    }

    private static void delete(String path) throws Exception {
        exec("rm -f \"$D/" + path + "\"");
    }

    /** Runs a shell command with $D set to the share's directory as the image configured it. */
    private static void exec(String command) throws Exception {
        ExecResult result = SAMBA.execInContainer("sh", "-c",
                // The image writes its effective configuration to smb.tmp (smbd --configfile); fail rather than write
                // to "/" when the share's path cannot be read.
                "D=$(cat /etc/samba/smb.tmp /etc/samba/smb.conf 2>/dev/null | sed -n 's/^[[:space:]]*path = //p' "
                        + "| head -n 1) && [ -n \"$D\" ] && " + command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + admin).timeout(Duration.ofSeconds(30));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        responses.add(response);
        return response;
    }
}
