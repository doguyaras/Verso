package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.FakeModelServer;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.Disposable;

/**
 * ADR-0051, without a Spring context or a database: the two ways the question service gives up a chat call reach an
 * OpenAI-compatible model server (llama-server) as a closed connection, which is what makes it stop generating and free
 * its slot. A streamed answer is cancelled by disposing the subscription (ADR-0035, the client left); a JSON answer by
 * interrupting the virtual thread that waits for it (ADR-0008 time bound; QuestionServiceImpl#call).
 */
class OpenAiCompatibleCancellationTest {

    private final FakeModelServer server = new FakeModelServer();
    private final OpenAiChatModel model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
            .baseUrl(server.baseUrl() + "/v1").apiKey("test-local-key").model("gemma-test").maxRetries(0)
            .timeout(Duration.ofSeconds(90)).build()).build();

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void stream_whenTheSubscriptionIsDisposed_closesTheServersConnection() throws Exception {
        StringBuilder events = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            events.append("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":1790935200,\"model\":")
                    .append("\"gemma-test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"kelime \"},")
                    .append("\"finish_reason\":null}]}\n\n");
        }
        server.answer(200, events + "data: [DONE]\n\n", "text/event-stream");
        server.trickle(Duration.ofMillis(100));

        CountDownLatch firstWord = new CountDownLatch(1);
        Disposable call = model.stream(new Prompt("soru")).subscribe(chunk -> firstWord.countDown());
        assertThat(firstWord.await(10, TimeUnit.SECONDS)).as("the stream started").isTrue();
        call.dispose();

        awaitAbandoned("disposing the stream closes the model server's connection");
    }

    @Test
    void call_whenTheWaitingThreadIsInterrupted_closesTheServersConnection() throws Exception {
        server.answer(200, "{\"id\":\"c\",\"object\":\"chat.completion\",\"created\":1790935200,\"model\":\"gemma-test\","
                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"geç\"},"
                + "\"finish_reason\":\"stop\"}]}");
        server.trickle(Duration.ofMillis(100));

        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                model.call(new Prompt("soru"));
            } catch (RuntimeException expected) {
                // the interrupted call fails; only the server's side is asserted
            }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (server.requests().isEmpty()) {
            assertThat(System.nanoTime()).as("the request reached the server").isLessThan(deadline);
            Thread.sleep(20);
        }
        Thread.sleep(300); // the answer has started trickling: the caller is blocked reading it
        caller.interrupt();

        awaitAbandoned("interrupting the call closes the model server's connection");
        caller.join(Duration.ofSeconds(10));
        assertThat(caller.isAlive()).isFalse();
    }

    private void awaitAbandoned(String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (server.abandoned() == 0) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(50);
        }
    }
}
