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

    /** Test review T17: every limit is checked, not only the first ones. */
    @Test
    void properties_whenAnyOtherLimitIsZeroOrNegative_failAtStartup() {
        var ingestion = INGESTION;
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 0, 50_000, DataSize.ofMegabytes(64),
                200, 4, 20, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion)).hasMessageContaining("max-text-chars");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 0, DataSize.ofMegabytes(64),
                200, 4, 20, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion)).hasMessageContaining("max-page-chars");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofBytes(0),
                200, 4, 20, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion)).hasMessageContaining("max-content-bytes");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofMegabytes(64),
                0, 4, 20, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion)).hasMessageContaining("max-documents-per-account");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofMegabytes(64),
                200, 0, 20, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion)).hasMessageContaining("max-concurrent-uploads");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofMegabytes(64),
                200, 4, 0, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion)).hasMessageContaining("max-queued-per-account");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofMegabytes(64),
                200, 4, 20, DataSize.ofBytes(0), 1000, 150, "m", 1024, ingestion)).hasMessageContaining("parser-memory");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofMegabytes(64),
                200, 4, 20, DataSize.ofMegabytes(256), 1000, -1, "m", 1024, ingestion)).hasMessageContaining("chunk-overlap");
        assertThatThrownBy(() -> new DocumentProperties.Ingestion(true, 5000, java.time.Duration.ofMinutes(10), 5,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(10), 0, 10, java.time.Duration.ofSeconds(30),
                java.time.Duration.ofMinutes(5))).hasMessageContaining("embedding-batch");
    }

    /** Test review T17: the module limit may not promise more than the multipart limit lets through. */
    @Test
    void configuration_whenTheModuleLimitExceedsTheMultipartLimit_failsToStart() {
        assertThatThrownBy(() -> new DocumentConfiguration(TestPdfs.properties(), DataSize.ofMegabytes(10)))
                .hasMessageContaining("must not exceed spring.servlet.multipart.max-file-size");
        new DocumentConfiguration(TestPdfs.properties(), DataSize.ofMegabytes(20));
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
                Duration.ofMinutes(10), 16, 10, Duration.ofSeconds(30), Duration.ofMinutes(5))).hasMessageContaining("lease");
        assertThatThrownBy(() -> new DocumentProperties.Ingestion(true, 5000, Duration.ofMinutes(10), 0,
                Duration.ofSeconds(30), Duration.ofMinutes(10), 16, 10, Duration.ofSeconds(30), Duration.ofMinutes(5))).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new DocumentProperties.Ingestion(true, 5000, Duration.ofMinutes(10), 5,
                Duration.ofMinutes(20), Duration.ofMinutes(10), 16, 10, Duration.ofSeconds(30), Duration.ofMinutes(5))).hasMessageContaining("max >= first");
    }

    private static DocumentProperties props(DataSize maxFile, int maxPages, int chunkSize, int overlap, String model,
                                            int dimensions) {
        return new DocumentProperties(maxFile, maxPages, 2_000_000, 50_000, DataSize.ofMegabytes(64), 200, 4, 20,
                DataSize.ofMegabytes(256), chunkSize, overlap,
                model, dimensions, INGESTION);
    }
}
