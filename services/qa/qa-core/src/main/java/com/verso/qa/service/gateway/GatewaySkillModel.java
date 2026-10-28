package com.verso.qa.service.gateway;

import com.verso.qa.api.dto.ModelTask;
import com.verso.qa.service.ModelBudgetExceededException;
import com.verso.work.api.SkillModel;
import com.verso.work.api.SkillModelException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The skill library's model port (ADR-0057) backed by the model gateway (ADR-0056): every step of a task skill run
 * goes through the {@code SKILL_RUN} route, with its fallbacks, the mode rules, the hard budgets and the usage record
 * (the routing word {@code skill:<id>:<step>} is kept on the usage row; never user text). {@code @Primary}: the work
 * module's own adapter on the built-in {@code ChatModel} may also exist (its {@code @ConditionalOnMissingBean} depends on
 * the registration order); the runtime gets this one.
 *
 * <p>The slot ({@link #tryAcquire}) keeps the work module's own bulkhead size ({@code verso.work.model-concurrency},
 * default 1): one run holds one slot for all of its calls, as before. Failure words: {@code MODEL_TIMEOUT},
 * {@code MODEL_BUDGET_EXCEEDED} (every model of the route over its hard budget) and {@code MODEL_UNAVAILABLE}. Logs carry
 * the routing word, the reason and the exception type only (llm-rules 2.1).
 */
@Component
@Primary
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class GatewaySkillModel implements SkillModel {

    private static final Logger log = LoggerFactory.getLogger(GatewaySkillModel.class);
    private static final ExecutorService CALLS = Executors.newVirtualThreadPerTaskExecutor();
    /** The routing words the skill runtime sends: fixed words from the skill definition. */
    static final Pattern TASK_WORD = Pattern.compile("skill:[A-Za-z0-9:_.-]{1,190}");

    private final ModelGateway gateway;
    private final Semaphore slots;

    public GatewaySkillModel(ModelGateway gateway, Environment environment) {
        this.gateway = gateway;
        int concurrency = environment.getProperty("verso.work.model-concurrency", Integer.class, 1);
        this.slots = new Semaphore(Math.max(1, Math.min(16, concurrency)));
    }

    @Override
    public Optional<Lease> tryAcquire(Duration wait) {
        try {
            if (!slots.tryAcquire(wait.toMillis(), TimeUnit.MILLISECONDS)) return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        AtomicBoolean released = new AtomicBoolean();
        return Optional.of(() -> {
            if (released.compareAndSet(false, true)) slots.release();
        });
    }

    @Override
    public String complete(Call call) {
        // An unexpected word is not stored (the usage row's CHECK); the call still goes through the SKILL_RUN route.
        String detail = call.task() != null && TASK_WORD.matcher(call.task()).matches() ? call.task() : null;
        ChatModel model = gateway.route(ModelTask.SKILL_RUN, null, detail);
        Prompt prompt = new Prompt(List.of(new SystemMessage(call.system()), new UserMessage(call.user())));
        Future<ChatResponse> future = CALLS.submit(() -> model.call(prompt));
        try {
            ChatResponse response = future.get(call.timeout().toMillis(), TimeUnit.MILLISECONDS);
            String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) throw failed(detail, "EMPTY_ANSWER", "MODEL_UNAVAILABLE", null);
            return text;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw failed(detail, "TIMEOUT", "MODEL_TIMEOUT", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SkillModelException("MODEL_UNAVAILABLE");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ModelBudgetExceededException) {
                throw failed(detail, "HARD_LIMIT", "MODEL_BUDGET_EXCEEDED", cause);
            }
            throw failed(detail, "CALL_FAILED", "MODEL_UNAVAILABLE", cause);
        }
    }

    private static SkillModelException failed(String task, String why, String reason, Throwable cause) {
        // Type only: a client exception's message may quote the prompt (llm-rules 2.1).
        log.warn("Skill model call failed: reason={} task={} exceptionType={}", why, task == null ? "-" : task,
                cause == null ? "-" : cause.getClass().getSimpleName());
        return new SkillModelException(reason);
    }

    /** The first model of the SKILL_RUN route (the built-in model when the route is empty). */
    @Override
    public String modelId() {
        try {
            return gateway.firstModel(ModelTask.SKILL_RUN);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
