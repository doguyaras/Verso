package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.FakeModelServer;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoPostgres;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cloud mode with Anthropic (ADR-0006, ADR-0013), end to end through the real auto-configured client against a fake
 * Anthropic API: only the rules, the question and the passages leave (no file name), the configured model and limits
 * are sent, a failing provider is asked once (ADR-0008), and every response says "cloud".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "VERSO_AI_MODE=cloud", "VERSO_CHAT_PROVIDER=anthropic",
        "CLOUD_CHAT_MODEL=claude-test", "spring.ai.anthropic.api-key=test-anthropic-key"})
// Not @VersoTestEnvironment: that one replaces the chat model; here the real Anthropic client answers.
@ContextConfiguration(initializers = {VersoPostgres.Initializer.class, TestIdp.Initializer.class,
        VersoTestEnvironment.Models.class})
@Import(TestEmbeddingModel.Config.class)
class AnthropicCloudModeTest extends CloudModeTestSupport {

    static final FakeModelServer ANTHROPIC = new FakeModelServer();

    @DynamicPropertySource
    static void anthropic(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.anthropic.base-url", ANTHROPIC::baseUrl);
    }

    @AfterAll
    static void stop() {
        ANTHROPIC.close();
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    ChatModel chatModel;

    @Override
    FakeModelServer provider() {
        return ANTHROPIC;
    }

    @Test
    void ask_whenAPassageMatches_sendsOnlyRulesQuestionAndPassagesToTheProvider() throws Exception {
        UUID id = ready("maas-bordrosu.pdf", "topic-leave Annual leave is twenty working days.");
        ANTHROPIC.answer(200, message("Yıllık izin yirmi iş günüdür [1]."));

        HttpResponse<String> response = ask("topic-leave Yillik izin kac gun?");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Rag-Mode")).hasValue("cloud");
        assertThat(response.body()).contains("\"mode\":\"cloud\"", "\"model\":\"claude-test\"", "\"found\":true",
                "\"documentId\":\"" + id + "\"");
        assertThat(ANTHROPIC.requests()).hasSize(1);
        FakeModelServer.Recorded request = ANTHROPIC.requests().getFirst();
        assertThat(request.path()).isEqualTo("/v1/messages");
        assertThat(request.header("x-api-key")).isEqualTo("test-anthropic-key");
        assertThat(request.body().replace(" ", ""))
                .contains("\"model\":\"claude-test\"", "\"max_tokens\":2048", "\"system\"")
                .as("no sampling override (review F2)").doesNotContain("\"temperature\"")
                .contains("[[BELGE1]](sayfa1)", "Soru:topic-leaveYillikizinkacgun?")
                .as("no tools are offered (llm-rules 3.2)").doesNotContain("\"tools\"");
        assertThat(request.body()).as("the file name stays home (ADR-0006 decision 2)").doesNotContain("maas-bordrosu");
        assertThat(request.header("X-Stainless-Timeout")).as("30 s per call (ADR-0008)").isIn(null, "30");
        assertNoLogContains("Yillik izin", "twenty working days", "test-anthropic-key", "maas-bordrosu");
        assertThat(chatModel).isNotInstanceOf(OllamaChatModel.class);
    }

    @Test
    void ask_whenTheProviderFails_asksOnceAndAnswers503WithoutProviderText() throws Exception {
        ready("izin.pdf", "topic-leave Annual leave rules.");
        ANTHROPIC.answer(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"MarkerProviderEcho\"}}");

        HttpResponse<String> response = ask("topic-leave izin?");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"code\":11001").doesNotContain("MarkerProviderEcho");
        assertThat(response.headers().firstValue("X-Rag-Mode")).hasValue("cloud");
        Thread.sleep(1500); // an SDK retry would arrive within its backoff
        assertThat(ANTHROPIC.requests()).as("no retry (ADR-0008)").hasSize(1);
        assertNoLogContains("MarkerProviderEcho", "test-anthropic-key");
    }

    @Test
    void ask_whenNothingIsRelevant_nothingLeavesTheHost() throws Exception {
        ready("izin.pdf", "topic-leave Annual leave rules.");

        HttpResponse<String> response = ask("topic-mars How many moons?");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"found\":false");
        assertThat(ANTHROPIC.requests()).isEmpty();
    }

    @Test
    void actuatorInfo_whenCloud_namesTheProviderModelAndTheLocalEmbeddingModel() throws Exception {
        HttpResponse<String> info = info();
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(info.body()).contains("\"mode\":\"cloud\"", "\"chatModel\":\"claude-test\"",
                "\"embeddingModel\":\"test-embedding\"");
        assertThat(info.headers().firstValue("X-Rag-Mode")).hasValue("cloud");
    }

    /** Phase 10 review: the panel's system screen reads the cloud mode from /v1/info. */
    @Test
    void info_whenCloud_namesTheProviderModelForThePanel() throws Exception {
        HttpResponse<String> info = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/info"))
                .header("Authorization", TestIdp.bearer(account)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(info.statusCode()).isEqualTo(200);
        assertThat(info.body()).isEqualTo(
                "{\"mode\":\"cloud\",\"chatModel\":\"claude-test\",\"embeddingModel\":\"test-embedding\"}");
    }

    private static String message(String text) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }
}
