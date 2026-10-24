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
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.FilteredHostException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;

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
    /** Lines of a streamed answer the fake server wrote, and whether a write failed (the client closed). */
    private static final java.util.concurrent.atomic.AtomicInteger STREAMED = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicBoolean STREAM_BROKEN =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final int STREAM_WORDS = 100;
    private static final HttpServer OLLAMA = fakeOllama();

    @Autowired
    org.springframework.web.reactive.function.client.WebClient.Builder webClients;

    @DynamicPropertySource
    static void ollama(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.ollama.base-url", () -> "http://127.0.0.1:" + OLLAMA.getAddress().getPort());
    }

    @Autowired
    OllamaChatModel chatModel;

    @Autowired
    RestClient.Builder restClients;

    @Autowired
    ApplicationContext context;

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

    /**
     * ADR-0006 decision 1.2: Boot's HTTP clients (the one under the Ollama client) connect to internal addresses only.
     * A public address is refused before any packet leaves, so the test needs no network.
     */
    @Test
    void outboundClients_whenAddressIsPublic_refuseToConnect_andInternalOnesWork() {
        RestClient client = restClients.build();
        assertThatThrownBy(() -> client.get().uri("http://203.0.113.9:11434/api/tags").retrieve().toBodilessEntity())
                .hasRootCauseInstanceOf(FilteredHostException.class);
        status = 200;
        assertThat(client.post().uri("http://127.0.0.1:" + OLLAMA.getAddress().getPort() + "/api/chat")
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
    }

    /**
     * ADR-0035: the streamed chat call goes through Boot's WebClient, so the same address filter applies to it (llm-rules
     * 1.1); a public address is refused before any packet leaves.
     */
    @Test
    void streamingClient_whenAddressIsPublic_refusesToConnect() {
        org.springframework.web.reactive.function.client.WebClient client = webClients.build();
        assertThatThrownBy(() -> client.get().uri("http://203.0.113.9:11434/api/tags").retrieve().toBodilessEntity()
                .block(java.time.Duration.ofSeconds(20))).hasStackTraceContaining("Filtered");
    }

    /**
     * ADR-0035: disposing the subscription of a streamed answer closes the connection to Ollama (Reactor Netty), so the
     * server's next writes fail and generation stops; the answer is not read to its end in the background.
     */
    @Test
    void stream_whenDisposed_closesTheConnectionToTheModelServer() throws Exception {
        STREAMED.set(0);
        STREAM_BROKEN.set(false);
        List<String> words = new CopyOnWriteArrayList<>();
        reactor.core.Disposable call = chatModel.stream(prompt())
                .subscribe(response -> words.add(response.getResult().getOutput().getText()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (words.size() < 2) {
            assertThat(System.nanoTime()).as("first words arrive").isLessThan(deadline);
            Thread.sleep(20);
        }

        call.dispose();

        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!STREAM_BROKEN.get()) {
            assertThat(System.nanoTime()).as("the server sees the connection closed").isLessThan(deadline);
            Thread.sleep(50);
        }
        assertThat(STREAMED.get()).isLessThan(STREAM_WORDS);
        assertThat(REQUESTS.getLast().replace(" ", "")).contains("\"stream\":true");
    }

    /** Local mode creates no cloud client at all (ADR-0013): the cloud starters are on the classpath, not in use. */
    @Test
    void localMode_whenStarted_hasNoCloudChatClient() {
        assertThat(context.getBeanNamesForType(AnthropicChatModel.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OpenAiChatModel.class)).isEmpty();
        assertThat(context.getBeanNamesForType(OllamaChatModel.class)).hasSize(1);
    }

    /** llm-rules 1.5: no prompt or completion in observations, no exporter that could ship them anywhere. */
    @Test
    void observations_whenConfigured_carryNoContentAndExportNowhere() {
        for (String setting : List.of("spring.ai.chat.observations.log-prompt", "spring.ai.chat.observations.log-completion",
                "spring.ai.chat.observations.include-error-logging", "spring.ai.chat.client.observations.log-prompt",
                "spring.ai.chat.client.observations.log-completion")) {
            assertThat(context.getEnvironment().getProperty(setting, Boolean.class, false)).as(setting).isFalse();
        }
        assertThat(ClassUtils.isPresent("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter", null)).isFalse();
        assertThat(ClassUtils.isPresent("io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter", null)).isFalse();
    }

    private static Prompt prompt() {
        return new Prompt(List.of(new SystemMessage("kurallar"), new UserMessage("Soru: kaç gün?")));
    }

    /** Ollama's streaming format: one JSON object per line, a word every 100 ms, "done" on the last. */
    private static void streamWords(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
        exchange.sendResponseHeaders(200, 0);
        try (var out = exchange.getResponseBody()) {
            for (int i = 0; i < STREAM_WORDS; i++) {
                boolean last = i == STREAM_WORDS - 1;
                String line = "{\"model\":\"test-chat\",\"created_at\":\"2026-10-03T00:00:00Z\",\"message\":{\"role\":"
                        + "\"assistant\",\"content\":\"kelime \"},\"done\":" + last + "}\n";
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
                STREAMED.incrementAndGet();
                Thread.sleep(100);
            }
        } catch (IOException e) {
            STREAM_BROKEN.set(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private static HttpServer fakeOllama() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/chat", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                REQUESTS.add(request);
                if (status == 200 && request.replace(" ", "").contains("\"stream\":true")) {
                    streamWords(exchange);
                    return;
                }
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
