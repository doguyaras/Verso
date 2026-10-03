package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import com.verso.support.VersoTestEnvironment;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The real, auto-configured Ollama chat client against a fake Ollama server (phase 5 review R1/L1/L2): the request
 * carries the configured model and options (verso.yml: think off, temperature 0.1, 512 tokens), and a failing model is
 * asked exactly once (ADR-0008: no retry on the synchronous path), without the provider's text in any log (llm-rules 2.3).
 */
@SpringBootTest
@VersoTestEnvironment
class OllamaChatClientTest {

    private static final List<String> REQUESTS = new CopyOnWriteArrayList<>();
    private static volatile int status = 200;
    private static final HttpServer OLLAMA = fakeOllama();

    @DynamicPropertySource
    static void ollama(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    }

    @Autowired
    OllamaChatModel chatModel;

    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        REQUESTS.clear();
        status = 200;
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        appender.stop();
    }

    @AfterAll
    static void stop() {
        OLLAMA.stop(0);
    }

    @Test
    void call_sendsTheConfiguredModelAndOptions() {
        String answer = chatModel.call(prompt()).getResult().getOutput().getText();

        assertThat(answer).isEqualTo("Yirmi gün [1].");
        assertThat(REQUESTS).hasSize(1);
        assertThat(REQUESTS.getFirst().replace(" ", ""))
                .contains("\"model\":\"test-chat\"", "\"think\":false", "\"temperature\":0.1", "\"num_predict\":512")
                .contains("\"role\":\"system\"", "\"role\":\"user\"");
    }

    @Test
    void call_whenTheModelFails_asksExactlyOnceAndLogsNoProviderText() throws Exception {
        status = 500;

        CompletableFuture<Void> call = CompletableFuture.runAsync(() -> chatModel.call(prompt()));
        // With the default retry (10 attempts, 2 s backoff) this would run for minutes.
        assertThatThrownBy(() -> call.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(RuntimeException.class);
        Thread.sleep(2500); // a retry after the first backoff would show up here
        assertThat(REQUESTS).hasSize(1);
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            assertThat(text).as(event.getLoggerName()).doesNotContain("MarkerProviderEcho");
        }
    }

    private static Prompt prompt() {
        return new Prompt(List.of(new SystemMessage("kurallar"), new UserMessage("Soru: kaç gün?")));
    }

    private static HttpServer fakeOllama() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/chat", exchange -> {
                REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String body = status == 200
                        ? "{\"model\":\"test-chat\",\"created_at\":\"2026-10-03T00:00:00Z\",\"message\":{\"role\":\"assistant\","
                          + "\"content\":\"Yirmi gün [1].\"},\"done\":true,\"done_reason\":\"stop\",\"total_duration\":1,"
                          + "\"load_duration\":1,\"prompt_eval_count\":1,\"prompt_eval_duration\":1,\"eval_count\":1,"
                          + "\"eval_duration\":1}"
                        : "{\"error\":\"MarkerProviderEcho of the prompt\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
