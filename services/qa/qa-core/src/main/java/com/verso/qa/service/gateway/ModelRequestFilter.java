package com.verso.qa.service.gateway;

import com.verso.qa.api.dto.ModelTask;
import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * The privacy gateway hook (ADR-0056): a filter chain around every call to an EXTERNAL connection. The request passes
 * through {@link #beforeSend} in order, the response (or every streamed chunk) through {@link #afterReceive} in
 * reverse order. The next step plugs the Turkish PII pseudonymisation in here (TCKN, VKN, IBAN, phone, plate, then
 * names via a local NER model): mask before send with session-consistent placeholders, unmask after receive. Until
 * then the only filter is {@link PassThrough}.
 *
 * <p>Rules for a filter: never log the prompt or the answer (llm-rules 2.1); never send anything new out (a filter
 * runs inside the installation); fail closed (an exception stops the call, it never sends the unfiltered prompt).
 */
public interface ModelRequestFilter {

    /** The name stored with every external call ({@code qa.model_usage.filter_policy}). */
    String policy();

    Prompt beforeSend(Prompt prompt, Call call);

    ChatResponse afterReceive(ChatResponse response, Call call);

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

    /** Sends and returns everything unchanged; the policy is recorded so the egress log says so. */
    final class PassThrough implements ModelRequestFilter {

        @Override
        public String policy() {
            return "PASS_THROUGH";
        }

        @Override
        public Prompt beforeSend(Prompt prompt, Call call) {
            return prompt;
        }

        @Override
        public ChatResponse afterReceive(ChatResponse response, Call call) {
            return response;
        }
    }
}
