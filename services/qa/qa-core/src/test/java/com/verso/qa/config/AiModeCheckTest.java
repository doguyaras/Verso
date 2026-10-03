package com.verso.qa.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** ADR-0006 decision 1.3, ADR-0013: settings that break the mode's promise stop the start; messages carry no values. */
class AiModeCheckTest {

    @Test
    void local_whenEverythingIsOllamaOnAnInternalAddress_starts() {
        assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://ollama:11434"))).doesNotThrowAnyException();
        assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://127.0.0.1:11434"))).doesNotThrowAnyException();
        assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://172.18.0.4:11434"))).doesNotThrowAnyException();
        assertThatCode(() -> AiModeCheck.verify(AiMode.LOCAL, local("http://[::1]:11434"))).doesNotThrowAnyException();
    }

    @Test
    void local_whenTheChatProviderIsACloudOne_refusesToStart() {
        MockEnvironment env = local("http://ollama:11434").withProperty("spring.ai.model.chat", "anthropic");
        assertThatThrownBy(() -> AiModeCheck.verify(AiMode.LOCAL, env)).hasMessageContaining("spring.ai.model.chat=ollama");
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
