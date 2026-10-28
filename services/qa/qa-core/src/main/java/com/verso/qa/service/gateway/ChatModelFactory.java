package com.verso.qa.service.gateway;
import com.verso.qa.repository.ModelConnection;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.verso.qa.api.dto.ModelProviderType;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.LocalModelEgress;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.bedrock.converse.BedrockProxyChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy.Builder;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Builds one Spring AI {@link ChatModel} per connection and model, natively per provider (ADR-0056): no static
 * auto-configuration, no sidecar. Every client is built the same way:
 *
 * <ul>
 *   <li>no client retry (ADR-0008: none on the synchronous path; the gateway's fallback chain is the retry) and the
 *       mode's time bound as the client timeout (the caller's bound still wins);</li>
 *   <li>an egress guard on the client's own HTTP stack: in local mode {@link LocalModelEgress} (internal addresses only),
 *       in cloud mode {@link EgressGuards} (this connection's host only). Ollama goes through Boot's builders and so
 *       through the application's internal-addresses-only filter in both modes;</li>
 *   <li>a cloud provider is never built in local mode (the third guard after save and startup, llm-rules 1.1);</li>
 *   <li>no content logging: the SDK log switches are refused at startup (AiModeCheck), observations stay NOOP.</li>
 * </ul>
 *
 * The decrypted key lives only in the built client.
 */
public class ChatModelFactory {

    /** Short answers: the same bound as the built-in clients (config/verso.yml), small for a connection test. */
    public static final int ANSWER_MAX_TOKENS = 2048;
    public static final int TEST_MAX_TOKENS = 8;

    private final AiMode mode;
    private final Duration timeout;
    private final Supplier<RestClient.Builder> restClients;
    private final Supplier<WebClient.Builder> webClients;

    public ChatModelFactory(AiMode mode, Duration timeout, Supplier<RestClient.Builder> restClients,
                            Supplier<WebClient.Builder> webClients) {
        this.mode = mode;
        this.timeout = timeout;
        this.restClients = restClients;
        this.webClients = webClients;
    }

    /**
     * @param key the decrypted key, or null for a keyless connection
     * @throws IllegalStateException for a cloud provider in local mode
     */
    public ChatModel build(ModelConnection c, String model, String key, int maxTokens) {
        if (mode == AiMode.LOCAL && (!c.type().selfHostable() || c.external())) {
            throw new IllegalStateException("A cloud model connection cannot be used in local mode (llm-rules 1.1)");
        }
        String baseUrl = ProviderEndpoints.blankToNull(c.baseUrl()) != null ? c.baseUrl().strip()
                : ProviderEndpoints.defaultBaseUrl(c.type());
        return switch (c.type()) {
            case OPENAI, MISTRAL, OPENAI_COMPATIBLE -> openAi(c, baseUrl, model, key, maxTokens, false);
            case AZURE_OPENAI -> openAi(c, baseUrl, model, key, maxTokens, true);
            case ANTHROPIC -> anthropic(c, baseUrl, model, key, maxTokens);
            case OLLAMA -> ollama(baseUrl, model, maxTokens);
            case BEDROCK -> bedrock(c, model, key, maxTokens);
            case GOOGLE_GENAI -> google(c, model, key, maxTokens);
        };
    }

    private ChatModel openAi(ModelConnection c, String baseUrl, String model, String key, int maxTokens, boolean azure) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().baseUrl(baseUrl).model(model)
                .maxTokens(maxTokens).timeout(timeout).maxRetries(0).streamUsage(true)
                // A keyless OpenAI-compatible server (a local llama-server without --api-key) still needs a value.
                .apiKey(key != null ? key : "none");
        if (azure) options.azure(true).deploymentName(c.deployment());
        return OpenAiChatModel.builder().options(options.build())
                .httpClientBuilderCustomizer(builder -> builder.interceptor(guard(c))).build();
    }

    private ChatModel anthropic(ModelConnection c, String baseUrl, String model, String key, int maxTokens) {
        AnthropicChatOptions options = AnthropicChatOptions.builder().baseUrl(baseUrl).apiKey(key).model(model)
                .maxTokens(maxTokens).timeout(timeout).maxRetries(0).build();
        return AnthropicChatModel.builder().options(options)
                .httpClientBuilderCustomizer(builder -> builder.interceptor(guard(c))).build();
    }

    /** Boot's builders: the internal-addresses-only filter of the application applies (QaConfiguration). */
    private ChatModel ollama(String baseUrl, String model, int maxTokens) {
        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).restClientBuilder(restClients.get())
                .webClientBuilder(webClients.get()).build();
        OllamaChatOptions options = OllamaChatOptions.builder().model(model).numPredict(maxTokens).temperature(0.1)
                .disableThinking().build();
        return OllamaChatModel.builder().ollamaApi(api).options(options).retryTemplate(noRetry()).build();
    }

    private ChatModel bedrock(ModelConnection c, String model, String key, int maxTokens) {
        int colon = key.indexOf(':');
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(key.substring(0, colon).strip(), key.substring(colon + 1).strip()));
        Region region = Region.of(c.region());
        URI endpoint = ProviderEndpoints.blankToNull(c.baseUrl()) == null ? null : URI.create(c.baseUrl().strip());
        String host = c.egressHost();
        BedrockRuntimeClient sync = BedrockRuntimeClient.builder().region(region).credentialsProvider(credentials)
                .httpClientBuilder(ApacheHttpClient.builder().socketTimeout(timeout).connectionTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(o -> o.apiCallTimeout(timeout).retryPolicy(noAwsRetry())
                        .addExecutionInterceptor(EgressGuards.aws(host)))
                .applyMutation(b -> {
                    if (endpoint != null) b.endpointOverride(endpoint);
                }).build();
        BedrockRuntimeAsyncClient async = BedrockRuntimeAsyncClient.builder().region(region)
                .credentialsProvider(credentials)
                .httpClientBuilder(NettyNioAsyncHttpClient.builder().readTimeout(timeout)
                        .connectionTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(o -> o.apiCallTimeout(timeout).retryPolicy(noAwsRetry())
                        .addExecutionInterceptor(EgressGuards.aws(host)))
                .applyMutation(b -> {
                    if (endpoint != null) b.endpointOverride(endpoint);
                }).build();
        BedrockChatOptions options = BedrockChatOptions.builder().model(model).maxTokens(maxTokens).build();
        return BedrockProxyChatModel.builder().bedrockRuntimeClient(sync).bedrockRuntimeAsyncClient(async)
                .options(options).build();
    }

    private static software.amazon.awssdk.core.retry.RetryPolicy noAwsRetry() {
        Builder policy = software.amazon.awssdk.core.retry.RetryPolicy.builder().numRetries(0);
        return policy.build();
    }

    private ChatModel google(ModelConnection c, String model, String key, int maxTokens) {
        HttpOptions.Builder http = HttpOptions.builder().timeout((int) timeout.toMillis())
                .retryOptions(HttpRetryOptions.builder().attempts(1).build());
        if (ProviderEndpoints.blankToNull(c.baseUrl()) != null) http.baseUrl(c.baseUrl().strip());
        Client.Builder client = Client.builder().httpOptions(http.build());
        if (ProviderEndpoints.blankToNull(c.project()) != null) {
            try {
                GoogleCredentials credentials = GoogleCredentials.fromStream(
                        new ByteArrayInputStream(key.getBytes(StandardCharsets.UTF_8)))
                        .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
                client.vertexAI(true).project(c.project()).location(c.region()).credentials(credentials);
            } catch (IOException e) {
                // The JSON parser's message could quote the key file.
                throw new IllegalStateException("The service account key is not valid: " + e.getClass().getSimpleName());
            }
        } else {
            client.apiKey(key);
        }
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder().model(model).maxOutputTokens(maxTokens)
                .build();
        return GoogleGenAiChatModel.builder().genAiClient(client.build()).options(options).retryTemplate(noRetry())
                .build();
    }

    /** Local mode: internal addresses only (ADR-0051's guard); cloud mode: the connection's own host only. */
    private okhttp3.Interceptor guard(ModelConnection c) {
        return mode == AiMode.LOCAL ? new LocalModelEgress() : EgressGuards.okHttp(c.egressHost());
    }

    private static RetryTemplate noRetry() {
        return new RetryTemplate(RetryPolicy.builder().maxRetries(0).build());
    }
}
