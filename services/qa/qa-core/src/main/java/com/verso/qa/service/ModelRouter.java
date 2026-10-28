package com.verso.qa.service;

import com.verso.qa.api.dto.ModelTask;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Picks the model of a task (ADR-0056). Called on the caller's thread (its security context names the team); the
 * returned {@link ChatModel} may then run anywhere. Its calls try the route's models in order, fall back on 5xx, 429 and
 * network failures, skip models over a hard budget and count every call.
 */
@FunctionalInterface
public interface ModelRouter {

    /**
     * @param choice a user's model choice id (GET /v1/models), already validated, or null
     */
    ChatModel route(ModelTask task, String choice);

    /**
     * A user's model choice, checked before any work: null passes; one that is not offered is 400
     * {@code MODEL_CHOICE_INVALID}. A router without choices offers none.
     */
    default String validChoice(String choice) {
        if (choice == null) return null;
        throw new com.verso.qa.exception.QaServiceException(com.verso.qa.exception.QaErrorCode.MODEL_CHOICE_INVALID,
                "NOT_OFFERED");
    }

    /** One model for every task: the built-in behaviour, and the unit tests'. */
    static ModelRouter fixed(ChatModel model) {
        return (task, choice) -> model;
    }
}
