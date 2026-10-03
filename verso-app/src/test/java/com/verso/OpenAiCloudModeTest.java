package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.FakeModelServer;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoPostgres;
import com.verso.support.VersoTestEnvironment;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cloud mode with an OpenAI-compatible API (ADR-0006, ADR-0013): the second provider, selected by configuration only,
 * through the real auto-configured client against a fake endpoint (OPENAI_BASE_URL).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "VERSO_AI_MODE=cloud", "VERSO_CHAT_PROVIDER=openai",
        "CLOUD_CHAT_MODEL=gpt-test", "spring.ai.openai.api-key=test-openai-key"})
@ContextConfiguration(initializers = {VersoPostgres.Initializer.class, TestIdp.Initializer.class,
        VersoTestEnvironment.Models.class})
@Import(TestEmbeddingModel.Config.class)
class OpenAiCloudModeTest extends CloudModeTestSupport {

    static final FakeModelServer OPENAI = new FakeModelServer();

    @DynamicPropertySource
    static void openAi(DynamicPropertyRegistry registry) {
        registry.add("OPENAI_BASE_URL", () -> OPENAI.baseUrl() + "/v1");
    }

    @AfterAll
    static void stop() {
        OPENAI.close();
    }

    @Override
    FakeModelServer provider() {
        return OPENAI;
    }

    @Test
    void ask_whenAPassageMatches_callsTheCompatibleEndpointWithTheConfiguredModel() throws Exception {
        ready("izin.pdf", "topic-leave Annual leave is twenty working days.");
        OPENAI.answer(200, completion("Yıllık izin yirmi iş günüdür [1]."));

        HttpResponse<String> response = ask("topic-leave Yillik izin kac gun?");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Rag-Mode")).hasValue("cloud");
        assertThat(response.body()).contains("\"mode\":\"cloud\"", "\"model\":\"gpt-test\"", "\"found\":true");
        assertThat(OPENAI.requests()).hasSize(1);
        FakeModelServer.Recorded request = OPENAI.requests().getFirst();
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.header("Authorization")).isEqualTo("Bearer test-openai-key");
        assertThat(request.body().replace(" ", "")).contains("\"model\":\"gpt-test\"", "\"temperature\":0.1")
                .doesNotContain("\"tools\"").doesNotContain("izin.pdf");
    }

    @Test
    void ask_whenTheProviderFails_asksOnce() throws Exception {
        ready("izin.pdf", "topic-leave Annual leave rules.");
        OPENAI.answer(500, "{\"error\":{\"message\":\"MarkerProviderEcho\",\"type\":\"server_error\"}}");

        HttpResponse<String> response = ask("topic-leave izin?");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"code\":11001").doesNotContain("MarkerProviderEcho");
        Thread.sleep(1500);
        assertThat(OPENAI.requests()).as("no retry (ADR-0008)").hasSize(1);
        assertNoLogContains("MarkerProviderEcho", "test-openai-key");
    }

    private static String completion(String text) {
        return "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1790935200,\"model\":\"gpt-test\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},"
                + "\"logprobs\":null,\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }
}
