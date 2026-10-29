package com.verso.qa.service.gateway;

import com.verso.qa.api.dto.ModelTask;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * The privacy gateway hook (ADR-0056): every call to an EXTERNAL connection opens one {@link Exchange}; the request
 * passes through {@link Exchange#beforeSend}, the response (or the stream) through {@code afterReceive}. An exchange
 * holds the state of exactly one call (ADR-0059: the placeholder mapping) and is dropped with it. Local calls never
 * open one.
 *
 * <p>Rules for a filter: never log the prompt or the answer (llm-rules 2.1); never send anything new out (a filter
 * runs inside the installation); fail closed (an exception stops the call, it never sends the unfiltered prompt).
 */
public interface ModelRequestFilter {

    /** The name stored with every external call ({@code qa.model_usage.filter_policy}). */
    String policy();

    Exchange open(Call call);

    /** One external call's filtering. */
    interface Exchange {
        Prompt beforeSend(Prompt prompt);

        ChatResponse afterReceive(ChatResponse response);

        /** A streamed answer; may emit one last chunk of its own when text was held back. */
        Flux<ChatResponse> afterReceive(Flux<ChatResponse> stream);

        /** What the usage row records about the filtering: the policy and counts only. */
        Filtered filtered();
    }

    /**
     * What a call's filter did, for the usage row (ADR-0059).
     *
     * @param masked distinct values replaced by a placeholder, by type ({@code TCKN}, {@code IBAN}, ...); never values
     */
    record Filtered(String policy, Map<String, Integer> masked) {
        /** A local call: nothing left the installation, nothing was filtered. */
        public static final Filtered NONE = new Filtered("NONE", Map.of());

        public Filtered {
            masked = Map.copyOf(masked);
        }
    }

    /**
     * What one external call is about: the task, the connection and the kinds of data it carries (the egress log).
     *
     * @param categories what leaves the installation, by kind, never content
     */
    record Call(ModelTask task, java.util.UUID connectionId, List<EgressCategory> categories) {
        public Call {
            categories = List.copyOf(categories);
        }
    }

    /** The kinds of data a task sends (llm-rules 1.2, 3.6, 3.8): the egress log records these, never the text. */
    enum EgressCategory {
        SYSTEM_INSTRUCTIONS, QUESTION, PASSAGES, CONVERSATION_TURNS, SYSTEM_MODEL_NAMES, PROCEDURE_STEPS, SKILL_FORM_INPUT,
        TEST_PROMPT;

        static List<EgressCategory> of(ModelTask task) {
            return switch (task) {
                case ANSWER -> List.of(SYSTEM_INSTRUCTIONS, QUESTION, PASSAGES);
                case FOLLOW_UP -> List.of(SYSTEM_INSTRUCTIONS, QUESTION, CONVERSATION_TURNS);
                case DECOMPOSITION -> List.of(SYSTEM_INSTRUCTIONS, QUESTION, SYSTEM_MODEL_NAMES);
                case SKILL_CONDENSE -> List.of(SYSTEM_INSTRUCTIONS, PROCEDURE_STEPS);
                // A task skill step (ADR-0057): the user's form fields and the retrieved passages it cites.
                case SKILL_RUN -> List.of(SYSTEM_INSTRUCTIONS, SKILL_FORM_INPUT, PASSAGES);
                case EMBEDDING -> List.of();
            };
        }
    }

    /** Sends and returns everything unchanged (the admin turned masking off); the policy says so in the egress log. */
    final class PassThrough implements ModelRequestFilter {

        static final String POLICY = "PASS_THROUGH";

        @Override
        public String policy() {
            return POLICY;
        }

        @Override
        public Exchange open(Call call) {
            return new Exchange() {
                @Override
                public Prompt beforeSend(Prompt prompt) {
                    return prompt;
                }

                @Override
                public ChatResponse afterReceive(ChatResponse response) {
                    return response;
                }

                @Override
                public Flux<ChatResponse> afterReceive(Flux<ChatResponse> stream) {
                    return stream;
                }

                @Override
                public Filtered filtered() {
                    return new Filtered(POLICY, Map.of());
                }
            };
        }
    }
}
