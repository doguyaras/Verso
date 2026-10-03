package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.config.QaProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ModelCircuitBreakerTest {

    private Instant now = Instant.parse("2026-10-03T10:00:00Z");
    private final ModelCircuitBreaker breaker = new ModelCircuitBreaker(
            new QaProperties(5, 0.45, 1500, 2, Duration.ofSeconds(5), 2, Duration.ofSeconds(15),
                Duration.ofSeconds(90), Duration.ofSeconds(30)),
            new Clock() {
                @Override
                public ZoneOffset getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now;
                }
            });

    @Test
    void opensAfterConsecutiveFailures_andOnlyForThePause() {
        breaker.recordFailure();
        assertThat(breaker.isOpen()).as("one failure is not an outage").isFalse();
        breaker.recordFailure();
        assertThat(breaker.isOpen()).isTrue();
        now = now.plusSeconds(14);
        assertThat(breaker.isOpen()).isTrue();
        now = now.plusSeconds(1);
        assertThat(breaker.isOpen()).as("half open after the pause").isFalse();
    }

    @Test
    void halfOpen_whenTheTrialFails_opensAgainAtOnce_andASuccessCloses() {
        breaker.recordFailure();
        breaker.recordFailure();
        now = now.plusSeconds(15);
        breaker.recordFailure();
        assertThat(breaker.isOpen()).isTrue();
        now = now.plusSeconds(15);
        breaker.recordSuccess();
        breaker.recordFailure();
        assertThat(breaker.isOpen()).as("a success resets the count").isFalse();
    }
}
