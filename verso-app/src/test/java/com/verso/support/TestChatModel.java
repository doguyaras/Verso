package com.verso.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stands in for the chat model in application tests (no model download in CI): answers with a configurable function of
 * the prompt, can fail, and keeps the last prompt so tests can check what would have left the application. One
 * instance per JVM; tests reset it.
 */
public final class TestChatModel implements ChatModel {

    public static final TestChatModel INSTANCE = new TestChatModel();
    public static final String DEFAULT_ANSWER = "Belgeye göre cevap budur [1].";

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private volatile Function<Prompt, String> answer = prompt -> DEFAULT_ANSWER;
    private volatile Prompt lastPrompt;

    private TestChatModel() {}

    /** Registered as the primary ChatModel; the Ollama one stays unused. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        ChatModel testChatModel() {
            return INSTANCE;
        }
    }

    public void reset() {
        calls.set(0);
        failure.set(null);
        answer = prompt -> DEFAULT_ANSWER;
        lastPrompt = null;
    }

    public void answer(String text) {
        answer = prompt -> text;
    }

    public void answer(Function<Prompt, String> function) {
        answer = function;
    }

    public void failWith(RuntimeException exception) {
        failure.set(exception);
    }

    public int calls() {
        return calls.get();
    }

    public Prompt lastPrompt() {
        return lastPrompt;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        calls.incrementAndGet();
        lastPrompt = prompt;
        RuntimeException error = failure.get();
        if (error != null) throw error;
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(prompt)))));
    }
}
