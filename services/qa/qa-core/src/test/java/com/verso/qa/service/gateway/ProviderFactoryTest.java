package com.verso.qa.service.gateway;
import com.verso.qa.repository.ModelConnection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.qa.api.dto.ModelConnectionRequest;
import com.verso.qa.api.dto.ModelProviderType;
import com.verso.qa.config.AiMode;
import com.verso.qa.exception.QaServiceException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * ADR-0056: one native Spring AI client per provider type, the type-specific field rules, the locality and the strict
 * local mode's refusal. No network: building a client opens no connection.
 */
class ProviderFactoryTest {

    private static final String KEY = "synthetic-" + "x".repeat(12) + "-0000";

    private static ChatModelFactory factory(AiMode mode) {
        return new ChatModelFactory(mode, Duration.ofSeconds(5), RestClient::builder, WebClient::builder);
    }

    static ModelConnection connection(ModelProviderType type, String baseUrl, String region, String deployment,
                                      String project) {
        String host = ProviderEndpoints.egressHost(type, baseUrl, region, project);
        return new ModelConnection(UUID.randomUUID(), "c", type, baseUrl, region, deployment, project, List.of("m"),
                true, ProviderEndpoints.locality(type, host), host, null, null, 0, null, null, null, Instant.now(),
                Instant.now(), 0);
    }

    @Test
    void factory_buildsTheNativeClientOfEveryProviderType() {
        ChatModelFactory cloud = factory(AiMode.CLOUD);
        assertThat(cloud.build(connection(ModelProviderType.OPENAI, null, null, null, null), "gpt", KEY, 16))
                .isInstanceOf(OpenAiChatModel.class);
        assertThat(cloud.build(connection(ModelProviderType.AZURE_OPENAI, "https://firma.openai.azure.com", null, "dep",
                null), "gpt", KEY, 16)).isInstanceOf(OpenAiChatModel.class);
        assertThat(cloud.build(connection(ModelProviderType.MISTRAL, null, null, null, null), "mistral-small", KEY, 16))
                .isInstanceOf(OpenAiChatModel.class);
        assertThat(cloud.build(connection(ModelProviderType.OPENAI_COMPATIBLE, "https://gw.firma.example/v1", null,
                null, null), "llama", null, 16)).isInstanceOf(OpenAiChatModel.class);
        assertThat(cloud.build(connection(ModelProviderType.ANTHROPIC, null, null, null, null), "claude", KEY, 16))
                .isInstanceOf(AnthropicChatModel.class);
        assertThat(cloud.build(connection(ModelProviderType.BEDROCK, null, "eu-central-1", null, null), "anthropic.x",
                "AKIDSYNTHETIC:secretSYNTHETIC", 16)).isInstanceOf(BedrockProxyChatModel.class);
        assertThat(cloud.build(connection(ModelProviderType.GOOGLE_GENAI, null, null, null, null), "gemini", KEY, 16))
                .isInstanceOf(GoogleGenAiChatModel.class);
        assertThat(cloud.build(connection(ModelProviderType.OLLAMA, "http://127.0.0.1:11434", null, null, null),
                "gemma", null, 16)).isInstanceOf(OllamaChatModel.class);
    }

    @Test
    void localMode_buildsOnlyInternalSelfHostedClients() {
        ChatModelFactory local = factory(AiMode.LOCAL);
        ModelConnection internal = connection(ModelProviderType.OPENAI_COMPATIBLE, "http://10.0.0.5:8080/v1", null,
                null, null);
        assertThat(internal.locality()).isEqualTo(ModelConnection.Locality.LOCAL);
        ChatModel model = local.build(internal, "gemma", null, 16);
        assertThat(model).isInstanceOf(OpenAiChatModel.class);

        for (ModelProviderType type : List.of(ModelProviderType.OPENAI, ModelProviderType.ANTHROPIC,
                ModelProviderType.MISTRAL, ModelProviderType.GOOGLE_GENAI)) {
            assertThatThrownBy(() -> local.build(connection(type, null, null, null, null), "m", KEY, 16))
                    .as(type.name()).isInstanceOf(IllegalStateException.class).hasMessageContaining("local mode");
        }
        ModelConnection publicServer = connection(ModelProviderType.OPENAI_COMPATIBLE, "https://8.8.8.8/v1", null,
                null, null);
        assertThat(publicServer.locality()).isEqualTo(ModelConnection.Locality.EXTERNAL);
        assertThatThrownBy(() -> local.build(publicServer, "m", null, 16)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startupCheck_refusesEnabledCloudConnectionsInLocalMode_byIdOnly() {
        ModelConnection cloud = connection(ModelProviderType.ANTHROPIC, null, null, null, null);

        assertThatThrownBy(() -> GatewayLifecycle.refuse(List.of(cloud))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(cloud.id().toString()).hasMessageContaining("llm-rules 1.1")
                .hasMessageNotContaining("api.anthropic.com");
        GatewayLifecycle.refuse(List.of());
    }

    @Test
    void endpoints_defaultHostsAndLocality() {
        assertThat(ProviderEndpoints.egressHost(ModelProviderType.OPENAI, null, null, null)).isEqualTo("api.openai.com");
        assertThat(ProviderEndpoints.egressHost(ModelProviderType.ANTHROPIC, null, null, null))
                .isEqualTo("api.anthropic.com");
        assertThat(ProviderEndpoints.egressHost(ModelProviderType.MISTRAL, null, null, null)).isEqualTo("api.mistral.ai");
        assertThat(ProviderEndpoints.egressHost(ModelProviderType.BEDROCK, null, "eu-central-1", null))
                .isEqualTo("bedrock-runtime.eu-central-1.amazonaws.com");
        assertThat(ProviderEndpoints.egressHost(ModelProviderType.GOOGLE_GENAI, null, "europe-west4", "p"))
                .isEqualTo("europe-west4-aiplatform.googleapis.com");
        assertThat(ProviderEndpoints.locality(ModelProviderType.OLLAMA, "127.0.0.1")).isEqualTo(
                ModelConnection.Locality.LOCAL);
        assertThat(ProviderEndpoints.locality(ModelProviderType.OPENAI, "127.0.0.1")).as("a cloud type is never local")
                .isEqualTo(ModelConnection.Locality.EXTERNAL);
        assertThat(ProviderEndpoints.locality(ModelProviderType.OLLAMA, "no-such-host.invalid"))
                .as("an unresolvable name fails closed").isEqualTo(ModelConnection.Locality.EXTERNAL);
    }

    @Test
    void validation_followsTheTypeSpecificForm() {
        assertReason(request(ModelProviderType.AZURE_OPENAI, "https://x.openai.azure.com", null, null, null, KEY),
                "DEPLOYMENT_REQUIRED");
        assertReason(request(ModelProviderType.OPENAI_COMPATIBLE, null, null, null, null, null), "BASE_URL_REQUIRED");
        assertReason(request(ModelProviderType.BEDROCK, null, null, null, null, "a:b"), "REGION_REQUIRED");
        assertReason(request(ModelProviderType.BEDROCK, null, "eu-central-1", null, null, "no-colon"), "KEY_MALFORMED");
        assertReason(request(ModelProviderType.OPENAI, null, null, null, null, null), "KEY_REQUIRED");
        assertReason(request(ModelProviderType.OLLAMA, "http://127.0.0.1:11434", null, null, null, KEY), "KEY_NOT_USED");
        assertReason(request(ModelProviderType.OPENAI, "http://api.example.com/v1", null, null, null, KEY),
                "BASE_URL_NOT_HTTPS");
        assertReason(request(ModelProviderType.OPENAI, "https://user:pw@api.example.com/v1", null, null, null, KEY),
                "BASE_URL_MALFORMED");
        assertReason(request(ModelProviderType.OPENAI, "ftp://api.example.com", null, null, null, KEY),
                "BASE_URL_SCHEME");
        // Security review B2: the cloud metadata service is never a model server.
        assertReason(request(ModelProviderType.OPENAI_COMPATIBLE, "http://169.254.169.254/v1", null, null, null, null),
                "BASE_URL_FORBIDDEN");
        assertReason(request(ModelProviderType.OLLAMA, "https://0.0.0.0:11434", null, null, null, null),
                "BASE_URL_FORBIDDEN");
        ProviderEndpoints.validate(request(ModelProviderType.OPENAI_COMPATIBLE, "http://127.0.0.1:8080/v1", null, null,
                null, null), false, false);
        ProviderEndpoints.validate(request(ModelProviderType.OPENAI, null, null, null, null, null), false, true);
    }

    private static ModelConnectionRequest request(ModelProviderType type, String baseUrl, String region,
                                                  String deployment, String project, String key) {
        return new ModelConnectionRequest("c", type, baseUrl, region, deployment, project, List.of("m"), key, true);
    }

    private static void assertReason(ModelConnectionRequest request, String reason) {
        assertThatThrownBy(() -> ProviderEndpoints.validate(request, request.apiKey() != null, false))
                .isInstanceOf(QaServiceException.class)
                .extracting(e -> ((QaServiceException) e).getSafeLogReason()).isEqualTo(reason);
    }
}
