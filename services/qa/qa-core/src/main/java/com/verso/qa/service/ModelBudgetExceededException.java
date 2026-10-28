package com.verso.qa.service;

/**
 * Every model of the route is over a hard monthly budget (ADR-0056). The caller turns it into 429
 * {@code MODEL_BUDGET_EXCEEDED}; it is not a model failure (the circuit breaker does not count it). No message, no
 * stack trace.
 */
public final class ModelBudgetExceededException extends RuntimeException {

    public ModelBudgetExceededException() {
        super(null, null, false, false);
    }
}
