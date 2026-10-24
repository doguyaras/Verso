package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * The MCP server is off by default (ADR-0031): no MCP server, no tool, no route. A valid API token gets 404 for /mcp
 * and its metadata, exactly as for any path that does not exist; an MCP token is refused by the API chain (401). The
 * edge proxy answers 404 itself while VERSO_MCP_ENABLED is false. Same context as the other real-port tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class McpDisabledTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ApplicationContext context;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void mcp_whenDisabled_isNotFoundForAValidTokenAndHasNoServerOrTools() throws Exception {
        assertThat(context.getBeanNamesForType(McpStatelessSyncServer.class)).isEmpty();
        assertThat(context.containsBean("versoMcpTools")).isFalse();
        assertThat(context.containsBean("versoMcpSecurity")).isFalse();

        String token = TestIdp.bearer("acct-mcp-disabled");
        HttpResponse<String> mcp = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", token).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(mcp.statusCode()).isEqualTo(404);
        HttpResponse<String> metadata = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/.well-known/oauth-protected-resource/mcp"))
                .header("Authorization", token).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(metadata.statusCode()).as(metadata.body()).isEqualTo(404);
        // Without a token as well: Spring Security's own metadata filter of the API chain is taken off this path
        // (PlatformSecurityAutoConfiguration.versoWellKnownSecurity), so the API announces no Host-derived resource.
        for (String path : new String[]{"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp",
                "/.well-known/oauth-protected-resource/v1/documents"}) {
            HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(anonymous.statusCode()).as(path + " " + anonymous.body()).isEqualTo(404);
            assertThat(anonymous.body()).doesNotContain("\"resource\":");
        }

        String mcpToken = TestIdp.IDP.token().subject("acct-mcp-disabled").audience("verso-mcp").build();
        HttpResponse<String> refused = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + mcpToken).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(401);
    }

    /** The edge routes /mcp only from mcp-true.conf; the default file and compose's default say off. */
    @Test
    void edge_whenMcpIsOff_answers404ForMcpAndItsMetadata() throws Exception {
        Path root = Path.of("..");
        String nginx = Files.readString(root.resolve("deploy/edge/nginx.conf"));
        assertThat(nginx).contains("include /etc/nginx/mcp.conf;");
        for (String location : new String[]{"location = /mcp {", "location ^~ /.well-known/oauth-protected-resource {"}) {
            int start = nginx.indexOf(location);
            assertThat(start).as(location).isPositive();
            String block = nginx.substring(start, nginx.indexOf("proxy_pass", start));
            assertThat(block).contains("if ($mcp_enabled = 0)").contains("return 404;");
        }
        assertThat(Files.readString(root.resolve("deploy/edge/mcp-false.conf"))).contains("default 0;");
        assertThat(Files.readString(root.resolve("deploy/edge/mcp-true.conf"))).contains("default 1;");
        assertThat(Files.readString(root.resolve("compose.yaml")))
                .contains("./deploy/edge/mcp-${VERSO_MCP_ENABLED:-false}.conf:/etc/nginx/mcp.conf:ro")
                .contains("VERSO_MCP_ENABLED: ${VERSO_MCP_ENABLED:-false}");
    }
}
