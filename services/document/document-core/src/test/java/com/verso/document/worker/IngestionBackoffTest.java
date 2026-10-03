package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.testing.TestPdfs;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Phase 4 test review T13: the retry delay doubles from 30 s and is capped at 10 min (ADR-0011). */
class IngestionBackoffTest {

    private final IngestionWorker worker = new IngestionWorker(null, null, null, null, TestPdfs.properties(), null);

    @Test
    void backoff_whenAttemptsGrow_doublesAndCaps() {
        assertThat(worker.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(worker.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(worker.backoff(4)).isEqualTo(Duration.ofSeconds(240));
        assertThat(worker.backoff(6)).isEqualTo(Duration.ofMinutes(10));
        assertThat(worker.backoff(50)).isEqualTo(Duration.ofMinutes(10));
    }
}
