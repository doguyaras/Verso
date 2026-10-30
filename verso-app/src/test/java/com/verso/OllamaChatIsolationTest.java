package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.config.OllamaChatIsolation;
import com.verso.support.FakeModelServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * ADR-0062, without a Spring context or a database: the built-in Ollama chat model, rebuilt on its own model server,
 * keeps its options, never reaches the embedding server, and a question that gives up on it (the interrupt of
 * QuestionServiceImpl#call at the chat timeout) closes the connection, so the server can stop generating. While the
 * chat server hangs, the embedding server still answers: the two share no client, no connection and no process.
 */
class OllamaChatIsolationTest {

    private static final String CHAT_ANSWER = "{\"model\":\"test-chat\",\"created_at\":\"2026-10-30T00:00:00Z\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"Yirmi gün [1].\"},\"done\":true,\"done_reason\":\"stop\","
            + "\"total_duration\":1,\"load_duration\":1,\"prompt_eval_count\":1,\"prompt_eval_duration\":1,"
            + "\"eval_count\":1,\"eval_duration\":1}";

    private final FakeModelServer embeddingServer = new FakeModelServer();
    private final FakeModelServer chatServer = new FakeModelServer();

    @AfterEach
    void stop() {
        embeddingServer.close();
        chatServer.close();
    }

    @Test
    void onOwnServer_whenCalled_keepsTheOptionsAndNeverReachesTheEmbeddingServer() {
        chatServer.answer(200, CHAT_ANSWER);

        String answer = isolatedChat().call(prompt()).getResult().getOutput().getText();

        assertThat(answer).isEqualTo("Yirmi gün [1].");
        assertThat(embeddingServer.requests()).isEmpty();
        assertThat(chatServer.requests()).singleElement().satisfies(r -> {
            assertThat(r.path()).isEqualTo("/api/chat");
            assertThat(r.body().replace(" ", "")).contains("\"model\":\"test-chat\"", "\"think\":false",
                    "\"temperature\":0.1", "\"num_predict\":512");
        });
    }

    /**
     * Ollama answers a non-streamed /api/chat only when the generation ends: nothing, not even the headers, comes
     * before. The fake does the same (a silent wait, then the answer); a write after the client left fails, as Ollama's
     * request context is cancelled when the connection closes.
     */
    @Test
    void call_whenTheWaitingThreadIsInterrupted_closesTheChatServersConnection() throws Exception {
        chatServer.answer(200, CHAT_ANSWER);
        chatServer.delay(Duration.ofSeconds(4));
        chatServer.trickle(Duration.ofMillis(20));
        OllamaChatModel chat = isolatedChat();

        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                chat.call(prompt());
            } catch (RuntimeException expected) {
                // the interrupted call fails; only the server's side is asserted
            }
        });
        awaitRequest(chatServer);
        Thread.sleep(300); // the model is "generating": the caller waits for the response
        caller.interrupt();
        caller.join(Duration.ofSeconds(1));
        assertThat(caller.isAlive()).as("the interrupted caller returns at once, not after the answer").isFalse();

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (chatServer.abandoned() == 0) {
            assertThat(System.nanoTime()).as("interrupting the call closes the chat server's connection")
                    .isLessThan(deadline);
            Thread.sleep(50);
        }
        caller.join(Duration.ofSeconds(10));
        assertThat(caller.isAlive()).isFalse();
    }

    @Test
    void embed_whileTheChatServerHangs_isAnsweredByItsOwnServer() throws Exception {
        chatServer.delay(Duration.ofSeconds(20));
        embeddingServer.answer(200, "{\"model\":\"test-embedding\",\"embeddings\":[[0.1,0.2,0.3]]}");
        OllamaChatModel chat = isolatedChat();
        Thread stuck = Thread.ofVirtual().start(() -> {
            try {
                chat.call(prompt());
            } catch (RuntimeException expected) {
                // interrupted below
            }
        });
        awaitRequest(chatServer);

        long started = System.nanoTime();
        float[] vector = embeddings().embed("Yıllık izin kaç gün?");

        assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(embeddingServer.requests()).singleElement().satisfies(r -> assertThat(r.path()).isEqualTo("/api/embed"));
        stuck.interrupt();
        stuck.join(Duration.ofSeconds(10));
    }

    /** The auto-configured model (one OllamaApi for both models), then moved to its own server as the application does. */
    private OllamaChatModel isolatedChat() {
        OllamaChatModel shared = OllamaChatModel.builder().ollamaApi(api(embeddingServer)).options(OllamaChatOptions.builder()
                .model("test-chat").temperature(0.1).numPredict(512).disableThinking().build()).build();
        return OllamaChatIsolation.onOwnServer(shared, chatServer.baseUrl(), restClients(), WebClient.builder());
    }

    private OllamaEmbeddingModel embeddings() {
        return OllamaEmbeddingModel.builder().ollamaApi(api(embeddingServer))
                .options(OllamaEmbeddingOptions.builder().model("test-embedding").build()).build();
    }

    private static OllamaApi api(FakeModelServer server) {
        return OllamaApi.builder().baseUrl(server.baseUrl()).restClientBuilder(restClients())
                .webClientBuilder(WebClient.builder()).build();
    }

    /** Reactor Netty, as in the application (spring.http.clients.imperative.factory: reactor). */
    private static RestClient.Builder restClients() {
        return RestClient.builder().requestFactory(new ReactorClientHttpRequestFactory());
    }

    private static void awaitRequest(FakeModelServer server) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (server.requests().isEmpty()) {
            assertThat(System.nanoTime()).as("the request reached the server").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private static Prompt prompt() {
        return new Prompt(List.of(new SystemMessage("kurallar"), new UserMessage("Soru: kaç gün?")));
    }
}
