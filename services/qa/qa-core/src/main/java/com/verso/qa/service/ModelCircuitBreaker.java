package com.verso.qa.service;

import com.verso.qa.config.QaProperties;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Fail fast while the chat model is down (ADR-0008, phase 5 review R2): after {@code circuitFailures} consecutive
 * failures the circuit opens for {@code circuitOpen}; meanwhile questions get 503 at once instead of each waiting for
 * the HTTP timeout and holding a chat slot. The first question after the pause tries the model again (half open): a
 * success closes the circuit, a failure opens it for another pause.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ModelCircuitBreaker {

    private final QaProperties properties;
    private final Clock clock;
    private int consecutiveFailures;
    private Instant openUntil = Instant.MIN;

    @Autowired
    public ModelCircuitBreaker(QaProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ModelCircuitBreaker(QaProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** True while the model must not be asked. */
    public synchronized boolean isOpen() {
        return clock.instant().isBefore(openUntil);
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntil = Instant.MIN;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= properties.circuitFailures()) {
            openUntil = clock.instant().plus(properties.circuitOpen());
        }
    }

    /** Closes the circuit; for tests and operations. */
    public synchronized void reset() {
        recordSuccess();
    }
}
