package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.verso.connector.worker.ConnectorSyncWorker;
import com.verso.support.AuditTables;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SharePoint Online in strict local mode (ADR-0045; llm-rules 1.1 and 1.7): the API refuses a SharePoint connector, and a
 * row that reached the database another way (an older version, a restore, a mode switch) fails its run with
 * EGRESS_DENIED before any request. The start-up refusal of external egress in local mode is ConnectorEgressConfiguration's
 * (ConnectorEgressConfigurationTest). Same context as ConnectorApiTest (local mode, private allowlist).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.connector.sync-enabled=false",
                "verso.connector.allowed-networks=10.0.0.0/8,172.16.0.0/12"})
@VersoTestEnvironment
class SharePointLocalModeTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ConnectorSyncWorker worker;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String admin = TestIdp.IDP.token().subject("acct-spo-local-" + suffix).realmRoles("verso-admin").build();

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        AuditTables.update("DELETE FROM connector.connector");
        jdbc.update("DELETE FROM document.collection WHERE name LIKE 'Yerel alan %'");
    }

    @Test
    void sharePoint_whenTheInstallationIsInLocalMode_isRefusedByTheApiAndBySync() throws Exception {
        UUID collection = collection();
        HttpResponse<String> refused = send("POST", "/v1/admin/connectors", "{\"name\":\"SP " + suffix + "\",\"type\":"
                + "\"SHAREPOINT\",\"credentialRef\":\"SECRET_CONNECTOR_SHAREPOINT_CERT\",\"collectionId\":\"" + collection
                + "\",\"scheduleMinutes\":60,\"sharePoint\":{\"siteUrl\":\"https://contoso.sharepoint.com/sites/hr\","
                + "\"tenantId\":\"0a1b2c3d-4e5f-4061-8273-94a5b6c7d8e9\",\"clientId\":\"9e8d7c6b-5a49-4837-a625-14f3e2d1c0b9\","
                + "\"authMethod\":\"CERTIFICATE\"}}");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(refused.body()).contains("\"code\":14013");

        HttpResponse<String> smb = send("POST", "/v1/admin/connectors", ConnectorApiTest.body("Yerel-" + suffix, collection, ""));
        assertThat(smb.statusCode()).as(smb.body()).isEqualTo(201);
        UUID connector = UUID.fromString(JsonPath.read(smb.body(), "$.id"));
        AuditTables.update("UPDATE connector.connector SET type = 'SHAREPOINT', share = NULL, username = NULL, "
                + "host = 'contoso.sharepoint.com', port = 443, base_url = 'https://contoso.sharepoint.com/sites/hr', "
                + "tenant_id = '0a1b2c3d-4e5f-4061-8273-94a5b6c7d8e9', client_id = '9e8d7c6b-5a49-4837-a625-14f3e2d1c0b9', "
                + "auth_method = 'CERTIFICATE', credential_ref = 'SECRET_CONNECTOR_SHAREPOINT_CERT' WHERE id = ?", connector);

        assertThat(send("POST", "/v1/admin/connectors/" + connector + "/sync", null).statusCode()).isEqualTo(202);
        assertThat(worker.runDue()).isEqualTo(1);

        assertThat(AuditTables.strings("select status || '/' || coalesce(error_code, 'null') from connector.sync_run "
                + "where connector_id = ? order by started_at desc limit 1", connector)).containsExactly("FAILED/EGRESS_DENIED");
        HttpResponse<String> view = send("GET", "/v1/admin/connectors/" + connector, null);
        assertThat((String) JsonPath.read(view.body(), "$.type")).isEqualTo("SHAREPOINT");
        assertThat((String) JsonPath.read(view.body(), "$.lastErrorCode")).isEqualTo("EGRESS_DENIED");
    }

    private UUID collection() throws Exception {
        HttpResponse<String> created = send("POST", "/v1/collections",
                "{\"name\":\"Yerel alan " + UUID.randomUUID().toString().substring(0, 8) + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(created.body(), "$.id"));
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + admin);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
