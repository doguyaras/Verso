package com.verso.work.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The skill library settings fail closed at start-up (ADR-0057); the deploy defaults of config/verso.yml pass. */
class WorkPropertiesTest {

    private static WorkProperties properties(Duration openFor, Duration streamPoll, Duration maxStream) {
        return new WorkProperties(1, Duration.ofSeconds(2), Duration.ofSeconds(180), 3, openFor, 90, 500, 40,
                streamPoll, maxStream);
    }

    @Test
    void deployDefaults_start() {
        WorkProperties deploy = properties(Duration.ofSeconds(30), Duration.ofMillis(700), Duration.ofMinutes(20));
        assertThat(deploy.lease()).isEqualTo(Duration.ofSeconds(180).plusMinutes(1));
    }

    @Test
    void circuit_whenOpenForIsNegativeOrTooLong_doesNotStart() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(-1), Duration.ofMillis(700), Duration.ofMinutes(20)))
                .hasMessageContaining("open-for");
        assertThatThrownBy(() -> properties(Duration.ofMinutes(11), Duration.ofMillis(700), Duration.ofMinutes(20)))
                .hasMessageContaining("open-for");
        assertThat(properties(Duration.ZERO, Duration.ofMillis(700), Duration.ofMinutes(20)).openFor()).isZero();
    }

    @Test
    void stream_whenThePollOrTheBoundIsOutOfRange_doesNotStart() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(30), Duration.ofMillis(1), Duration.ofMinutes(20)))
                .hasMessageContaining("stream-poll");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(30), Duration.ofSeconds(11), Duration.ofMinutes(20)))
                .hasMessageContaining("stream-poll");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(30), Duration.ofMillis(700), Duration.ofMillis(500)))
                .hasMessageContaining("max-stream");
        assertThatThrownBy(() -> properties(Duration.ofSeconds(30), Duration.ofMillis(700), Duration.ofHours(2)))
                .hasMessageContaining("max-stream");
    }
}
