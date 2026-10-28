package com.verso.work.model;

import java.time.Duration;
import java.util.Optional;

/**
 * The skill runtime's only door to a language model (ADR-0057): the integration point of the provider-agnostic model
 * gateway. Today {@link ChatModelSkillModel} adapts the installation's Spring AI {@code ChatModel} (the same provider,
 * mode and egress rules as the question path, ADR-0006/0013); the gateway replaces it by declaring its own
 * {@code SkillModel} bean (the adapter is {@code @ConditionalOnMissingBean}), with routing per {@link Call#task()} and
 * its budgets behind {@link #tryAcquire}. Nothing else in the work module touches a chat client.
 *
 * <p>Contract for an implementation: never log a prompt or an answer (llm-rules 2.1); bound every call by
 * {@link Call#timeout()}; fail with {@link SkillModelException} and a fixed reason word.
 */
public interface SkillModel {

    /**
     * One completion.
     *
     * @param task    a fixed routing word ({@code skill:<skill-id>:<step-key>} or {@code skill:<skill-id>:repair}); no
     *                user text. The gateway routes and budgets by it.
     * @param timeout the most this call may take
     */
    record Call(String task, String system, String user, Duration timeout) {

        @Override
        public String toString() {
            return "Call[task=" + task + ", timeout=" + timeout + "]";
        }
    }

    /** A held model slot; closing it frees the slot. A run holds one for all of its calls. */
    interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * A slot for one run, waiting at most {@code wait}; empty when every slot stays busy (the caller answers 503
     * MODEL_BUSY). Same rule as the question path's chat slots: a bounded number of concurrent model users per
     * instance, a short wait, then a fast refusal.
     */
    Optional<Lease> tryAcquire(Duration wait);

    /** The answer's text. */
    String complete(Call call);

    /** The configured model's id for the audit log and the run record; null when unknown. */
    String modelId();
}
