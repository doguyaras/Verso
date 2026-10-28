package com.verso.work.model;

import com.verso.work.api.SkillModel;
import com.verso.work.api.SkillModelException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * {@link SkillModel} on the installation's Spring AI {@code ChatModel} (ADR-0057), until the model gateway provides
 * its own. Same three guards as the question path (AGENTS.md 5): a per-instance bulkhead of
 * {@code verso.work.model.concurrency} run slots with a short wait, a per-call time bound (the provider's HTTP client
 * has its own read timeout too), and a circuit that refuses at once for {@code open-for} after
 * {@code failure-threshold} failed calls in a row. Logs carry the task word, the reason and the exception type only.
 */
public class ChatModelSkillModel implements SkillModel {

    private static final Logger log = LoggerFactory.getLogger(ChatModelSkillModel.class);
    private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9._:/@+-]{1,100}");

    private final ChatModel chat;
    private final Semaphore slots;
    private final int failureThreshold;
    private final Duration openFor;
    private final Clock clock;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.MIN);

    public ChatModelSkillModel(ChatModel chat, int concurrency, int failureThreshold, Duration openFor, Clock clock) {
        this.chat = chat;
        this.slots = new Semaphore(concurrency);
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
        this.clock = clock;
    }

    @Override
    public Optional<Lease> tryAcquire(Duration wait) {
        try {
            if (!slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        java.util.concurrent.atomic.AtomicBoolean released = new java.util.concurrent.atomic.AtomicBoolean();
        return Optional.of(() -> {
            if (released.compareAndSet(false, true)) slots.release();
        });
    }

    @Override
    public String complete(Call call) {
        if (clock.instant().isBefore(openUntil.get())) {
            log.warn("Skill model call refused: reason=CIRCUIT_OPEN task={}", call.task());
            throw new SkillModelException("MODEL_UNAVAILABLE");
        }
        Prompt prompt = new Prompt(List.of(new SystemMessage(call.system()), new UserMessage(call.user())));
        Future<ChatResponse> future = CALLS.submit(() -> chat.call(prompt));
        try {
            ChatResponse response = future.get(call.timeout().toMillis(), TimeUnit.MILLISECONDS);
            String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) throw failed(call, "EMPTY_ANSWER", "MODEL_UNAVAILABLE", null);
            failures.set(0);
            return text;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw failed(call, "TIMEOUT", "MODEL_TIMEOUT", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SkillModelException("MODEL_UNAVAILABLE");
        } catch (ExecutionException e) {
            throw failed(call, "CALL_FAILED", "MODEL_UNAVAILABLE", e.getCause() != null ? e.getCause() : e);
        }
    }

    private SkillModelException failed(Call call, String why, String reason, Throwable cause) {
        if (failures.incrementAndGet() >= failureThreshold) {
            openUntil.set(clock.instant().plus(openFor));
            failures.set(0);
        }
        // Type only: a client exception's message may quote the prompt (llm-rules 2.1).
        log.warn("Skill model call failed: reason={} task={} exceptionType={}", why, call.task(),
                cause == null ? "-" : cause.getClass().getSimpleName());
        return new SkillModelException(reason);
    }

    @Override
    public String modelId() {
        try {
            var options = chat.getDefaultOptions();
            String model = options == null ? null : options.getModel();
            return model != null && MODEL_ID.matcher(model).matches() ? model : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
