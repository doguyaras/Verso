package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.FakeModelServer;
import com.verso.support.QaTables;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoPostgres;
import com.verso.support.VersoTestEnvironment;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The shared tier (ADR-0051): local mode with the chat on an OpenAI-compatible model server on the internal network
 * (llama.cpp's llama-server in deploy/compose.llamacpp.yaml), selected by configuration only, through the real
 * auto-configured OpenAI client against a stub server on 127.0.0.1. The mode stays "local"; the JSON and the streamed
 * answer (ADR-0035) keep the server-side citation check; a client that leaves and the local time bound (ADR-0008) both
 * close the connection to the model server, which is how llama-server learns to stop generating.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "VERSO_AI_MODE=local", "VERSO_CHAT_PROVIDER=openai",
        "CLOUD_CHAT_MODEL=gemma-test", "spring.ai.openai.api-key=test-local-key", "verso.qa.local-chat-timeout=3s",
        "OPENAI_CHAT_TIMEOUT=90s"})
@ContextConfiguration(initializers = {VersoPostgres.Initializer.class, TestIdp.Initializer.class,
        VersoTestEnvironment.Models.class})
@Import(TestEmbeddingModel.Config.class)
class LocalOpenAiModeTest extends CloudModeTestSupport {

    static final FakeModelServer SERVER = new FakeModelServer();

    @DynamicPropertySource
    static void server(DynamicPropertyRegistry registry) {
        registry.add("OPENAI_BASE_URL", () -> SERVER.baseUrl() + "/v1");
    }

    @AfterAll
    static void stop() {
        SERVER.close();
    }

    @Override
    FakeModelServer provider() {
        return SERVER;
    }

    @Test
    void ask_whenAPassageMatches_answersInLocalModeThroughTheCompatibleServer() throws Exception {
        UUID id = ready("izin.pdf", "topic-leave Annual leave is twenty working days.");
        SERVER.answer(200, completion("Yıllık izin yirmi iş günüdür [1] [4]."));

        HttpResponse<String> response = ask("topic-leave Yillik izin kac gun?");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Rag-Mode")).hasValue("local");
        assertThat(response.body()).contains("\"mode\":\"local\"", "\"model\":\"gemma-test\"", "\"found\":true",
                "\"documentId\":\"" + id + "\"").doesNotContain("[4]");
        FakeModelServer.Recorded request = SERVER.requests().getFirst();
        assertThat(request.path()).isEqualTo("/v1/chat/completions");
        assertThat(request.header("Authorization")).isEqualTo("Bearer test-local-key");
        assertThat(request.body().replace(" ", "")).contains("\"model\":\"gemma-test\"", "\"temperature\":0.1",
                "\"max_tokens\":512").doesNotContain("\"tools\"").doesNotContain("izin.pdf");
        assertThat(info().body()).contains("\"mode\":\"local\"", "\"chatModel\":\"gemma-test\"");
        assertNoLogContains("Yillik izin", "twenty working days", "test-local-key");
    }

    @Test
    void stream_whenAPassageMatches_relaysTheServerStreamWithServerCitations() throws Exception {
        UUID id = ready("maas-bordrosu.pdf", "topic-leave Annual leave is twenty working days.");
        SERVER.answer(200, sse(List.of("Yıllık izin ", "yirmi iş günüdür [1] [4].")), "text/event-stream");

        HttpResponse<String> response = http.send(stream("topic-leave Yillik izin kac gun?"),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-Rag-Mode")).hasValue("local");
        assertThat(response.body()).contains("event:delta", "Yıllık izin ", "event:citations", "event:done",
                "\"documentId\":\"" + id + "\"", "\"outcome\":\"ANSWERED\"", "\"mode\":\"local\"",
                "\"model\":\"gemma-test\"");
        String citations = response.body().substring(response.body().indexOf("event:citations"));
        assertThat(citations.substring(0, citations.indexOf("event:done"))).contains("günüdür [1].").doesNotContain("[4]");
        assertThat(SERVER.requests().getLast().body().replace(" ", "")).contains("\"stream\":true");
        assertNoLogContains("Yillik izin", "Yıllık izin", "twenty working days", "maas-bordrosu", "test-local-key");
    }

    /** ADR-0035: a client that leaves closes the model server's connection and frees the chat slot. */
    @Test
    void stream_whenTheClientLeaves_closesTheServerConnectionAndFreesTheSlot() throws Exception {
        ready("izin.pdf", "topic-leave Annual leave is twenty working days.");
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 60; i++) words.add("kelime ");
        SERVER.answer(200, sse(words), "text/event-stream");
        SERVER.trickle(Duration.ofMillis(100));

        HttpResponse<InputStream> response = http.send(stream("topic-leave Yıllık izin?"),
                HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && !line.equals("event:delta")) {
                // up to the first word
            }
            assertThat(line).isEqualTo("event:delta");
        }

        awaitAbandoned("the model server's connection is closed");
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ?", account)).as("nothing stored")
                .isZero();
        SERVER.reset();
        SERVER.answer(200, completion("Yirmi gün [1]."));
        assertThat(ask("topic-leave izin?").statusCode()).as("the chat slot is free again").isEqualTo(200);
    }

    /** ADR-0008: the local bound (3 s here, 90 s in production) answers 503 and closes the server's connection. */
    @Test
    void ask_whenTheServerIsTooSlow_answers503AtTheLocalBoundAndClosesTheConnection() throws Exception {
        ready("izin.pdf", "topic-leave Annual leave rules.");
        SERVER.answer(200, completion("late [1]."));
        SERVER.trickle(Duration.ofMillis(100));

        long started = System.nanoTime();
        HttpResponse<String> response = ask("topic-leave izin?");
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"code\":11001");
        assertThat(took).isBetween(Duration.ofMillis(2500), Duration.ofSeconds(8));
        awaitAbandoned("the abandoned generation is cancelled at the model server");
    }

    private void awaitAbandoned(String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (SERVER.abandoned() == 0) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private HttpRequest stream(String question) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/questions/stream"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question + "\"}"))
                .timeout(Duration.ofSeconds(30)).build();
    }

    /** OpenAI chat completion chunks as llama-server streams them, ending with [DONE]. */
    private static String sse(List<String> pieces) {
        StringBuilder out = new StringBuilder();
        for (String piece : pieces) {
            out.append("data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1790935200,")
                    .append("\"model\":\"gemma-test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",")
                    .append("\"content\":\"").append(piece).append("\"},\"finish_reason\":null}]}\n\n");
        }
        out.append("data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1790935200,")
                .append("\"model\":\"gemma-test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n")
                .append("data: [DONE]\n\n");
        return out.toString();
    }

    private static String completion(String text) {
        return "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1790935200,\"model\":\"gemma-test\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},"
                + "\"logprobs\":null,\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }
}
