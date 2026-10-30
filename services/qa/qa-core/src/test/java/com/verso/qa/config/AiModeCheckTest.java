package com.verso.qa.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

/** ADR-0006 decision 1.3, ADR-0013: settings that break the mode's promise stop the start; messages carry no values. */
class AiModeCheckTest {

    @Test
    void local_whenEverythingIsOllamaOnAnInternalAddress_starts() {
        assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://ollama:11434"))).doesNotThrowAnyException();
        assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://127.0.0.1:11434"))).doesNotThrowAnyException();
        assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://172.18.0.4:11434"))).doesNotThrowAnyException();
        assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://[::1]:11434"))).doesNotThrowAnyException();
        assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://my_ollama:11434"))).as("review F10")
                .doesNotThrowAnyException();
    }

    @Test
    void local_whenTheChatProviderIsACloudOne_refusesToStart() {
        MockEnvironment env = local("http://ollama:11434").withProperty("spring.ai.model.chat", "anthropic");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, env)).hasMessageContaining("spring.ai.model.chat")
                .hasMessageContaining("only allowed with verso.ai.mode=cloud");
    }

    @Test
    void local_whenOllamaIsAPublicAddress_refusesToStart() {
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://203.0.113.9:11434")))
                .hasMessageContaining("outside the internal network").hasMessageNotContaining("203.0.113.9");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://[2001:db8::1]:11434")))
                .hasMessageContaining("outside the internal network");
    }

    @Test
    void anyMode_whenEmbeddingsAreNotLocal_refusesToStart() {
        MockEnvironment env = local("http://ollama:11434").withProperty("spring.ai.model.embedding", "openai");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, env)).hasMessageContaining("llm-rules 1.2");
        MockEnvironment cloud = cloud("anthropic", "spring.ai.anthropic.api-key", "k")
                .withProperty("spring.ai.model.embedding", "openai");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.CLOUD, cloud)).hasMessageContaining("llm-rules 1.2");
    }

    @Test
    void anyMode_whenOllamaIsAPublicAddressOrNoUrl_refusesToStart() {
        MockEnvironment cloud = cloud("anthropic", "spring.ai.anthropic.api-key", "k")
                .withProperty("spring.ai.ollama.base-url", "http://203.0.113.9:11434");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.CLOUD, cloud)).as("review F4: embeddings leave in cloud mode too")
                .hasMessageContaining("outside the internal network");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, local("ollama:11434"))).hasMessageContaining("has no host");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://bad host:1"))).hasMessageContaining("not a valid URL");
    }

    @Test
    void cloud_whenTheProviderHasItsKey_starts() {
        assertThatCode(() -> AiModeCheck.verify(AiMode.CLOUD, cloud("anthropic", "spring.ai.anthropic.api-key", "k")))
                .doesNotThrowAnyException();
        assertThatCode(() -> AiModeCheck.verify(AiMode.CLOUD, cloud("openai", "spring.ai.openai.api-key", "k")))
                .doesNotThrowAnyException();
    }

    @Test
    void cloud_whenTheChatModelIsLocalOrUnknown_refusesToStart() {
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.CLOUD, cloud("ollama", "x", "k")))
                .hasMessageContaining("verso.ai.mode=cloud needs");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.CLOUD, cloud("mistral", "x", "k")))
                .hasMessageContaining("verso.ai.mode=cloud needs");
    }

    @Test
    void cloud_whenTheKeyIsMissing_refusesToStartWithoutPrintingAnything() {
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.CLOUD, cloud("anthropic", "spring.ai.openai.api-key", "k")))
                .hasMessageContaining("spring.ai.anthropic.api-key is missing").hasMessageContaining("/run/secrets");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.CLOUD, cloud("anthropic", "spring.ai.anthropic.api-key", " ")))
                .hasMessageContaining("is missing");
    }

    /** ADR-0056 security review B1: the gateway may build an SDK client at runtime, so the switches are refused always. */
    @Test
    void verify_whenAnSdkLogSwitchIsSet_refusesEvenWithOllama() {
        MockEnvironment ollama = new MockEnvironment().withProperty("spring.ai.model.embedding", "ollama")
                .withProperty("spring.ai.model.chat", "ollama").withProperty("spring.ai.ollama.base-url", "http://ollama:11434")
                .withProperty("ANTHROPIC_LOG", "debug");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, ollama)).hasMessageContaining("ANTHROPIC_LOG is set");
    }

    /** Phase 6 review B3/B4: the key only from the config tree; the SDKs' request logging stays off. */
    @Test
    void cloud_whenTheKeyComesFromTheEnvironmentOrSdkLoggingIsOn_refusesToStart() {
        MockEnvironment fromEnv = cloud("anthropic", "spring.ai.anthropic.api-key", "k");
        fromEnv.getPropertySources().addLast(new MapPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("SPRING_AI_ANTHROPIC_APIKEY", "k")));
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.CLOUD, fromEnv)).hasMessageContaining("must come from /run/secrets");

        MockEnvironment logging = cloud("openai", "spring.ai.openai.api-key", "k").withProperty("OPENAI_LOG", "debug");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.CLOUD, logging)).hasMessageContaining("OPENAI_LOG is set");
    }

    /** ADR-0051: the shared tier keeps local mode with an OpenAI-compatible server on the internal network. */
    @Test
    void local_whenTheChatIsAnInternalOpenAiCompatibleServerWithItsKey_starts() {
        for (String url : List.of("http://llama:8080/v1", "http://127.0.0.1:8080/v1", "http://10.0.3.7:8080/v1",
                "http://[::1]:8080/v1", "http://my_llama:8080/v1")) {
            assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, localOpenAi(url, "k"))).as(url)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void local_whenTheOpenAiCompatibleServerIsPublicOrHasNoUrl_refusesToStartWithoutTheAddress() {
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, localOpenAi("https://203.0.113.9/v1", "k")))
                .hasMessageContaining("spring.ai.openai.base-url points outside the internal network")
                .hasMessageNotContaining("203.0.113.9");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, localOpenAi("http://[2001:db8::1]/v1", "k")))
                .hasMessageContaining("outside the internal network");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, localOpenAi("", "k")))
                .hasMessageContaining("spring.ai.openai.base-url has no host");
    }

    @Test
    void local_whenTheOpenAiCompatibleServersKeyIsMissingOrFromTheEnvironment_refusesToStart() {
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, localOpenAi("http://llama:8080/v1", " ")))
                .hasMessageContaining("spring.ai.openai.api-key is missing").hasMessageContaining("compose.llamacpp.yaml");
        MockEnvironment fromEnv = localOpenAi("http://llama:8080/v1", "k");
        fromEnv.getPropertySources().addLast(new MapPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of("SPRING_AI_OPENAI_API_KEY", "k")));
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, fromEnv)).hasMessageContaining("must come from /run/secrets");
        MockEnvironment logging = localOpenAi("http://llama:8080/v1", "k").withProperty("OPENAI_LOG", "debug");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, logging)).hasMessageContaining("OPENAI_LOG is set");
    }

    /** ADR-0062: the chat model's own Ollama is held to the embedding server's rule; empty keeps the shared one. */
    @Test
    void local_whenTheOllamaChatServerIsPublic_refusesToStartWithoutTheAddress() {
        for (String url : List.of("http://ollama-chat:11434", "http://127.0.0.1:11435", "", " ")) {
            assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://ollama:11434")
                    .withProperty(OllamaChatIsolation.CHAT_BASE_URL, url))).as(url).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://ollama:11434")
                .withProperty(OllamaChatIsolation.CHAT_BASE_URL, "http://203.0.113.9:11434")))
                .hasMessageContaining("verso.ai.ollama-chat-base-url points outside the internal network")
                .hasMessageNotContaining("203.0.113.9");
    }

    private static MockEnvironment localOpenAi(String url, String key) {
        return local("http://ollama:11434").withProperty("spring.ai.model.chat", "openai")
                .withProperty("spring.ai.openai.base-url", url).withProperty("spring.ai.openai.api-key", key);
    }

    private static MockEnvironment local(String ollama) {
        return new MockEnvironment().withProperty("spring.ai.model.embedding", "ollama")
                .withProperty("spring.ai.model.chat", "ollama").withProperty("spring.ai.ollama.base-url", ollama);
    }

    private static MockEnvironment cloud(String provider, String keySetting, String key) {
        return new MockEnvironment().withProperty("spring.ai.model.embedding", "ollama")
                .withProperty("spring.ai.model.chat", provider).withProperty("spring.ai.ollama.base-url", "http://ollama:11434")
                .withProperty(keySetting, key);
    }
}
