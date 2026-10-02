package com.verso.document.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.testing.TestPdfs;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** A zero, negative or inconsistent limit stops the start instead of switching a protection off. */
class DocumentPropertiesTest {

    private static final DocumentProperties.Ingestion INGESTION = TestPdfs.properties().ingestion();

    @Test
    void properties_whenALimitIsNotPositive_failAtStartup() {
        assertThatThrownBy(() -> props(DataSize.ofBytes(0), 500, 1000, 150, "m", 1024)).hasMessageContaining("max-file-size");
        assertThatThrownBy(() -> props(DataSize.ofMegabytes(20), 0, 1000, 150, "m", 1024)).hasMessageContaining("max-pages");
        assertThatThrownBy(() -> props(DataSize.ofMegabytes(20), 500, 0, 0, "m", 1024)).hasMessageContaining("chunk-size");
    }

    @Test
    void properties_whenOverlapOrModelOrDimensionsAreWrong_failAtStartup() {
        assertThatThrownBy(() -> props(DataSize.ofMegabytes(20), 500, 100, 100, "m", 1024)).hasMessageContaining("chunk-overlap");
        assertThatThrownBy(() -> props(DataSize.ofMegabytes(20), 500, 1000, 150, " ", 1024)).hasMessageContaining("embedding-model");
        assertThatThrownBy(() -> props(DataSize.ofMegabytes(20), 500, 1000, 150, "m", 768))
                .hasMessageContaining("embedding-dimensions");
    }

    @Test
    void ingestion_whenLeaseOrBackoffIsNotPositive_failsAtStartup() {
        assertThatThrownBy(() -> new DocumentProperties.Ingestion(true, 5000, Duration.ZERO, 5, Duration.ofSeconds(30),
                Duration.ofMinutes(10), 16, 10)).hasMessageContaining("lease");
        assertThatThrownBy(() -> new DocumentProperties.Ingestion(true, 5000, Duration.ofMinutes(10), 0,
                Duration.ofSeconds(30), Duration.ofMinutes(10), 16, 10)).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new DocumentProperties.Ingestion(true, 5000, Duration.ofMinutes(10), 5,
                Duration.ofMinutes(20), Duration.ofMinutes(10), 16, 10)).hasMessageContaining("max >= first");
    }

    private static DocumentProperties props(DataSize maxFile, int maxPages, int chunkSize, int overlap, String model,
                                            int dimensions) {
        return new DocumentProperties(maxFile, maxPages, 2_000_000, 200, DataSize.ofMegabytes(256), chunkSize, overlap,
                model, dimensions, INGESTION);
    }
}
