package com.verso.qa.service.gateway;
import com.verso.qa.repository.ModelConnection;
import com.verso.qa.service.ModelBudgetExceededException;

import com.verso.qa.api.dto.ModelTask;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * One task's route as a {@link ChatModel} (ADR-0056): the models in order, each tried only when the one before failed
 * with a retryable failure (5xx, 429, network; {@link FailureClassifier}) and skipped when it is over a hard budget.
 * Any other failure stops the route: a refused key or an unknown model is a setting to fix, not an outage to hide. A
 * stream falls back only before its first token (a half-written answer is never continued by another model).
 *
 * <p>No retry of the same model, no own timeout: the caller's bound (ADR-0008) covers the whole route, and an interrupt
 * stops it. Logs carry the task, the step and the exception type, never the prompt, the answer or the provider's text
 * (llm-rules 2.1, 2.3).
 */
public final class RoutedChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RoutedChatModel.class);

    /** One model of the route; {@code connection} null is the built-in model. */
    public record Candidate(ModelConnection connection, String model) {
        public boolean external() {
            return connection != null && connection.external();
        }
    }

    /** What the route needs from the gateway: clients, budgets, filters and the usage record. */
    public interface Hooks {
        ChatModel client(Candidate candidate);

        boolean overBudget(Candidate candidate, String team);

        ModelRequestFilter filter();

        /** @param usage null when the provider reported none (or the call failed) */
        void record(Candidate candidate, ModelTask task, String team, String account, String outcome, Usage usage,
                    List<ModelRequestFilter.EgressCategory> sent);
    }

    private final Hooks hooks;
    private final ModelTask task;
    private final List<Candidate> candidates;
    private final String team;
    private final String account;

    RoutedChatModel(Hooks hooks, ModelTask task, List<Candidate> candidates, String team, String account) {
        this.hooks = hooks;
        this.task = task;
        this.candidates = List.copyOf(candidates);
        this.team = team;
        this.account = account;
    }

    public List<Candidate> candidates() {
        return candidates;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RuntimeException last = null;
        boolean blocked = false;
        for (int step = 0; step < candidates.size(); step++) {
            if (Thread.currentThread().isInterrupted()) break;
            Candidate candidate = candidates.get(step);
            if (hooks.overBudget(candidate, team)) {
                blocked = true;
                hooks.record(candidate, task, team, account, "BLOCKED", null, List.of());
                continue;
            }
            List<ModelRequestFilter.EgressCategory> sent = sent(candidate);
            ModelRequestFilter.Call call = call(candidate, sent);
            try {
                Prompt outgoing = candidate.external() ? hooks.filter().beforeSend(prompt, call) : prompt;
                ChatResponse response = hooks.client(candidate).call(outgoing);
                ChatResponse incoming = candidate.external() ? hooks.filter().afterReceive(response, call) : response;
                hooks.record(candidate, task, team, account, "OK", usageOf(incoming), sent);
                return incoming;
            } catch (RuntimeException e) {
                hooks.record(candidate, task, team, account, "FAILED", null, sent);
                last = e;
                // The caller gave up (its time bound interrupted this thread; OkHttp turns that into an
                // InterruptedIOException and may clear the flag): no next model, nothing more leaves (llm-review L1).
                if (interrupted(e)) {
                    Thread.currentThread().interrupt();
                    break;
                }
                FailureClassifier.Kind kind = FailureClassifier.classify(e);
                if (kind != FailureClassifier.Kind.RETRYABLE || step == candidates.size() - 1) break;
                log.warn("Model call failed, next model of the route: task={} step={} exceptionType={}", task, step,
                        e.getClass().getSimpleName());
            }
        }
        if (last != null) throw last;
        if (blocked) throw new ModelBudgetExceededException();
        throw new IllegalStateException("The route has no model");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return attempt(prompt, 0, false);
    }

    private Flux<ChatResponse> attempt(Prompt prompt, int step, boolean blockedBefore) {
        if (step >= candidates.size()) {
            return Flux.error(blockedBefore ? new ModelBudgetExceededException()
                    : new IllegalStateException("The route has no model"));
        }
        Candidate candidate = candidates.get(step);
        return Flux.defer(() -> {
            if (hooks.overBudget(candidate, team)) {
                hooks.record(candidate, task, team, account, "BLOCKED", null, List.of());
                return attempt(prompt, step + 1, true);
            }
            List<ModelRequestFilter.EgressCategory> sent = sent(candidate);
            ModelRequestFilter.Call call = call(candidate, sent);
            AtomicBoolean emitted = new AtomicBoolean();
            AtomicReference<Usage> usage = new AtomicReference<>();
            AtomicBoolean recorded = new AtomicBoolean();
            Prompt outgoing = candidate.external() ? hooks.filter().beforeSend(prompt, call) : prompt;
            return hooks.client(candidate).stream(outgoing)
                    .map(chunk -> candidate.external() ? hooks.filter().afterReceive(chunk, call) : chunk)
                    .doOnNext(chunk -> {
                        emitted.set(true);
                        Usage u = usageOf(chunk);
                        // Providers report usage on the last chunk (OpenAI with stream_options.include_usage).
                        if (u != null) usage.set(u);
                    })
                    .doOnComplete(() -> {
                        if (recorded.compareAndSet(false, true)) {
                            hooks.record(candidate, task, team, account, "OK", usage.get(), sent);
                        }
                    })
                    .doOnCancel(() -> {
                        // The client left: the tokens produced so far are not known; the call still counts.
                        if (recorded.compareAndSet(false, true)) {
                            hooks.record(candidate, task, team, account, "OK", usage.get(), sent);
                        }
                    })
                    .onErrorResume(error -> {
                        if (recorded.compareAndSet(false, true)) {
                            hooks.record(candidate, task, team, account, "FAILED", null, sent);
                        }
                        boolean retryable = FailureClassifier.classify(error) == FailureClassifier.Kind.RETRYABLE;
                        if (emitted.get() || !retryable || step == candidates.size() - 1) return Flux.error(error);
                        log.warn("Model stream failed before its first token, next model of the route: task={} step={} "
                                + "exceptionType={}", task, step, error.getClass().getSimpleName());
                        return attempt(prompt, step + 1, blockedBefore);
                    });
        });
    }

    static boolean interrupted(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return true;
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof InterruptedException || t instanceof java.io.InterruptedIOException) return true;
        }
        return false;
    }

    private List<ModelRequestFilter.EgressCategory> sent(Candidate candidate) {
        return candidate.external() ? ModelRequestFilter.EgressCategory.of(task) : List.of();
    }

    private ModelRequestFilter.Call call(Candidate candidate, List<ModelRequestFilter.EgressCategory> sent) {
        return new ModelRequestFilter.Call(task, candidate.connection() == null ? null : candidate.connection().id(),
                sent);
    }

    static Usage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return null;
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) return null;
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        return (prompt == null || prompt == 0) && (completion == null || completion == 0) ? null : usage;
    }
}
