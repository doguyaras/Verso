package com.verso.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import com.verso.qa.service.FollowUpRewriter;
import com.verso.qa.service.QuestionDecomposer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

/**
 * Stands in for the chat model in application tests (no model download in CI): answers with a configurable function of
 * the prompt, can fail, and keeps the last prompt so tests can check what would have left the application. One
 * instance per JVM; tests reset it.
 *
 * <p>A follow-up rewrite (ADR-0046, told apart by its system message) is answered and counted on its own: by default it
 * repeats the last question unchanged, so tests that ask in a conversation keep their retrieval, call count and last
 * (answer) prompt.
 */
public final class TestChatModel implements ChatModel {

    public static final TestChatModel INSTANCE = new TestChatModel();
    public static final String DEFAULT_ANSWER = "Belgeye göre cevap budur [1].";

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private volatile Function<Prompt, String> answer = prompt -> DEFAULT_ANSWER;
    private volatile Prompt lastPrompt;
    private final AtomicInteger rewriteCalls = new AtomicInteger();
    private volatile Function<Prompt, String> rewrite = TestChatModel::lastQuestion;
    private volatile Prompt lastRewritePrompt;
    private volatile Duration tokenDelay = Duration.ZERO;
    private final AtomicInteger emittedTokens = new AtomicInteger();
    private final AtomicBoolean streamCancelled = new AtomicBoolean();
    private final AtomicInteger decomposeCalls = new AtomicInteger();
    private volatile Function<Prompt, String> decompose = prompt -> "";
    private volatile Duration decomposeDelay = Duration.ZERO;

    private static final Pattern WORD = Pattern.compile("\\s*\\S+\\s*");

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
        rewriteCalls.set(0);
        rewrite = TestChatModel::lastQuestion;
        lastRewritePrompt = null;
        tokenDelay = Duration.ZERO;
        emittedTokens.set(0);
        streamCancelled.set(false);
        decomposeCalls.set(0);
        decompose = prompt -> "";
        decomposeDelay = Duration.ZERO;
    }

    /** What multi-step decompositions return (ADR-0055); the default is no valid decomposition (single pass). */
    public void decompose(Function<Prompt, String> function) {
        decompose = function;
    }

    /** A decomposition that takes this long (the time-out fallback, ADR-0055). */
    public void decomposeAfter(Duration delay) {
        decomposeDelay = delay;
    }

    public int decomposeCalls() {
        return decomposeCalls.get();
    }

    private static boolean isDecomposition(Prompt prompt) {
        return !prompt.getInstructions().isEmpty()
                && QuestionDecomposer.SYSTEM.equals(prompt.getInstructions().getFirst().getText());
    }

    /** A streamed answer emits one word every {@code delay} (ADR-0035 tests: cancellation, time bound). */
    public void streamEvery(Duration delay) {
        tokenDelay = delay;
    }

    /** Words the streams have produced so far; stops growing once the subscriber cancels. */
    public int emittedTokens() {
        return emittedTokens.get();
    }

    /** True once a subscriber cancelled a stream before its end (the client left, the time bound). */
    public boolean streamCancelled() {
        return streamCancelled.get();
    }

    /** The same answer word by word (white space kept), so the joined deltas are what call() would return. */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            calls.incrementAndGet();
            lastPrompt = prompt;
            RuntimeException error = failure.get();
            if (error != null) return Flux.<ChatResponse>error(error);
            Flux<String> words = Flux.fromIterable(words(answer.apply(prompt)));
            if (!tokenDelay.isZero()) words = words.delayElements(tokenDelay);
            return words.doOnNext(word -> emittedTokens.incrementAndGet())
                    .doOnCancel(() -> streamCancelled.set(true))
                    .map(word -> new ChatResponse(List.of(new Generation(new AssistantMessage(word)))));
        });
    }

    private static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) words.add(matcher.group());
        return words;
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

    /** What follow-up rewrites return (ADR-0046); the default repeats the question unchanged. */
    public void rewrite(Function<Prompt, String> function) {
        rewrite = function;
    }

    public int rewriteCalls() {
        return rewriteCalls.get();
    }

    public Prompt lastRewritePrompt() {
        return lastRewritePrompt;
    }

    private static boolean isRewrite(Prompt prompt) {
        return !prompt.getInstructions().isEmpty()
                && FollowUpRewriter.SYSTEM.equals(prompt.getInstructions().getFirst().getText());
    }

    private static String lastQuestion(Prompt prompt) {
        String user = prompt.getInstructions().getLast().getText();
        return user.substring(user.lastIndexOf("Son soru: ") + "Son soru: ".length());
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (isDecomposition(prompt)) {
            decomposeCalls.incrementAndGet();
            if (!decomposeDelay.isZero()) {
                try {
                    Thread.sleep(decomposeDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(decompose.apply(prompt)))));
        }
        if (isRewrite(prompt)) {
            rewriteCalls.incrementAndGet();
            lastRewritePrompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(rewrite.apply(prompt)))));
        }
        calls.incrementAndGet();
        lastPrompt = prompt;
        RuntimeException error = failure.get();
        if (error != null) throw error;
        return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(prompt)))));
    }
}
