package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.verso.support.FakeModelServer;
import com.verso.support.QaTables;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The strict local mode with the model gateway (ADR-0056, llm-rules 1.1): a cloud provider cannot be saved, enabled or
 * routed, nor a self-hosted server outside the internal network; a model server on the internal network can, and its
 * key follows the same write-only rules. Nothing is sent to a cloud address: the refused saves make no provider call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class ModelGatewayLocalModeTest {

    static final FakeModelServer LOCAL_SERVER = new FakeModelServer();

    @AfterAll
    static void stop() {
        LOCAL_SERVER.close();
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String admin = "Bearer " + TestIdp.IDP.token().subject("acct-gw-local-" + UUID.randomUUID())
            .realmRoles("verso-admin").build();

    @BeforeEach
    void setUp() {
        QaTables.resetGateway();
        LOCAL_SERVER.reset();
    }

    @AfterEach
    void tearDown() {
        QaTables.resetGateway();
    }

    @Test
    void cloudProviders_areRefusedAtSave_enabledOrNot_withoutAnyProviderCall() throws Exception {
        for (String type : new String[]{"OPENAI", "ANTHROPIC", "MISTRAL", "AZURE_OPENAI", "BEDROCK", "GOOGLE_GENAI"}) {
            for (boolean enabled : new boolean[]{true, false}) {
                HttpResponse<String> refused = create("{\"name\":\"Bulut " + type + "\",\"type\":\"" + type + "\","
                        + "\"baseUrl\":\"https://x.openai.azure.com\",\"region\":\"eu-central-1\",\"deployment\":\"d\","
                        + "\"models\":[\"m\"],\"apiKey\":\"AKIDSYNTHETIC:secretSYNTHETIC\",\"enabled\":" + enabled + "}");
                assertThat(refused.statusCode()).as("%s enabled=%s", type, enabled).isEqualTo(409);
                assertThat(refused.body()).contains("\"code\":11091").doesNotContain("SYNTHETIC");
            }
        }
        assertThat(QaTables.count("SELECT count(*) FROM qa.model_connection")).isZero();
    }

    @Test
    void selfHostedServer_outsideTheInternalNetwork_isRefused() throws Exception {
        HttpResponse<String> refused = create("{\"name\":\"Dis sunucu\",\"type\":\"OPENAI_COMPATIBLE\","
                + "\"baseUrl\":\"https://8.8.8.8/v1\",\"models\":[\"m\"]}");

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("\"code\":11091");
    }

    @Test
    void selfHostedServer_onTheInternalNetwork_isValidatedAndStored() throws Exception {
        LOCAL_SERVER.answer(200, "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gemma\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,"
                + "\"total_tokens\":4}}");

        HttpResponse<String> created = create("{\"name\":\"llama-server\",\"type\":\"OPENAI_COMPATIBLE\","
                + "\"baseUrl\":\"" + LOCAL_SERVER.baseUrl() + "/v1\",\"models\":[\"gemma\"],"
                + "\"apiKey\":\"local-SYNTHETIC-key-0000\"}");

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.body()).contains("\"locality\":\"LOCAL\"").doesNotContain("local-SYNTHETIC-key-0000");
        String id = JsonPath.read(created.body(), "$.id");
        HttpResponse<String> routed = send("PUT", "/v1/admin/model-gateway/routes/ANSWER",
                "{\"entries\":[{\"connectionId\":\"" + id + "\",\"model\":\"gemma\"}]}");
        assertThat(routed.statusCode()).isEqualTo(200);
        HttpResponse<String> trust = send("GET", "/v1/admin/trust", null);
        assertThat(JsonPath.<String>read(trust.body(), "$.models.connections[0].locality")).isEqualTo("LOCAL");
        assertThat(JsonPath.<String>read(trust.body(), "$.ai.mode")).isEqualTo("local");
    }

    private HttpResponse<String> create(String body) throws Exception {
        return send("POST", "/v1/admin/model-gateway/connections", body);
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", admin).timeout(Duration.ofSeconds(30));
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
