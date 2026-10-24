package com.verso.document.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.testing.TestPdfs;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** A zero, negative or inconsistent limit stops the start instead of switching a protection off. */
class DocumentPropertiesTest {

    private static final DocumentProperties.Ingestion INGESTION = TestPdfs.properties().ingestion();
    private static final DocumentProperties.Retrieval RETRIEVAL = TestPdfs.properties().retrieval();

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
                200, 4, 20, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion, RETRIEVAL)).hasMessageContaining("max-text-chars");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 0, DataSize.ofMegabytes(64),
                200, 4, 20, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion, RETRIEVAL)).hasMessageContaining("max-page-chars");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofBytes(0),
                200, 4, 20, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion, RETRIEVAL)).hasMessageContaining("max-content-bytes");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofMegabytes(64),
                0, 4, 20, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion, RETRIEVAL)).hasMessageContaining("max-documents-per-account");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofMegabytes(64),
                200, 0, 20, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion, RETRIEVAL)).hasMessageContaining("max-concurrent-uploads");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofMegabytes(64),
                200, 4, 0, DataSize.ofMegabytes(256), 1000, 150, "m", 1024, ingestion, RETRIEVAL)).hasMessageContaining("max-queued-per-account");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofMegabytes(64),
                200, 4, 20, DataSize.ofBytes(0), 1000, 150, "m", 1024, ingestion, RETRIEVAL)).hasMessageContaining("parser-memory");
        assertThatThrownBy(() -> new DocumentProperties(DataSize.ofMegabytes(20), 500, 100, 10, DataSize.ofMegabytes(64),
                200, 4, 20, DataSize.ofMegabytes(256), 1000, -1, "m", 1024, ingestion, RETRIEVAL)).hasMessageContaining("chunk-overlap");
        assertThatThrownBy(() -> new DocumentProperties.Ingestion(true, 5000, java.time.Duration.ofMinutes(10), 5,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(10), 0, 10, java.time.Duration.ofSeconds(30),
                java.time.Duration.ofMinutes(5))).hasMessageContaining("embedding-batch");
    }

    /** Test review T17: the module limit may not promise more than the multipart limit lets through. */
    @Test
    void configuration_whenTheModuleLimitExceedsTheMultipartLimit_failsToStart() {
        DoclingProperties off = TestPdfs.docling(false, null);
        assertThatThrownBy(() -> new DocumentConfiguration(TestPdfs.properties(), off, DataSize.ofMegabytes(10)))
                .hasMessageContaining("must not exceed spring.servlet.multipart.max-file-size");
        new DocumentConfiguration(TestPdfs.properties(), off, DataSize.ofMegabytes(20));
    }

    /** ADR-0032: a sidecar conversion may not outlive the claim lease (10 min), or a second worker claims the file. */
    @Test
    void configuration_whenTheSidecarTimeoutReachesTheLease_failsToStart() {
        DoclingProperties tooLong = TestPdfs.docling(true, "http://docling:5001", Duration.ofMinutes(10),
                Duration.ofSeconds(1), 3);
        assertThatThrownBy(() -> new DocumentConfiguration(TestPdfs.properties(), tooLong, DataSize.ofMegabytes(20)))
                .hasMessageContaining("document-timeout must be shorter than verso.document.ingestion.lease");
        // Off, the same value is never used and does not stop the start.
        new DocumentConfiguration(TestPdfs.properties(), TestPdfs.docling(false, null, Duration.ofMinutes(10),
                Duration.ofSeconds(1), 3), DataSize.ofMegabytes(20));
    }

    /** ADR-0032: an enabled sidecar needs a plain http(s) address; zero limits and BCP-47 language tags are refused. */
    @Test
    void docling_whenEnabledWithoutAUsableAddressOrWithZeroLimits_failsAtStartup() {
        assertThatThrownBy(() -> TestPdfs.docling(true, null)).hasMessageContaining("base-url");
        assertThatThrownBy(() -> TestPdfs.docling(true, "file:///etc/passwd")).hasMessageContaining("base-url");
        assertThatThrownBy(() -> TestPdfs.docling(true, "http://user:pw@docling:5001")).hasMessageContaining("credentials");
        assertThatThrownBy(() -> TestPdfs.docling(true, "http://docling:5001", Duration.ZERO, Duration.ofSeconds(1), 3))
                .hasMessageContaining("document-timeout");
        assertThatThrownBy(() -> TestPdfs.docling(true, "http://docling:5001", Duration.ofMinutes(5), Duration.ofSeconds(1), 0))
                .hasMessageContaining("circuit-failures");
        assertThatThrownBy(() -> new DoclingProperties(true, java.net.URI.create("http://docling:5001"), false,
                Duration.ofSeconds(2), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(1), 100,
                DataSize.ofMegabytes(32), java.util.List.of("tr", "en"), 1, 3, Duration.ofMinutes(5)))
                .hasMessageContaining("ocr-languages");
        // Disabled: no address needed (the default stack).
        TestPdfs.docling(false, null);
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

    /** ADR-0022: the fusion inputs are bounded; 0 lexical candidates is allowed (vector search only). */
    @Test
    void retrieval_whenACandidateCountOrRrfKIsOutOfRange_failsAtStartup() {
        Duration ten = Duration.ofSeconds(10);
        assertThatThrownBy(() -> new DocumentProperties.Retrieval(ten, 4, 0, 20, 60)).hasMessageContaining("vector-candidates");
        assertThatThrownBy(() -> new DocumentProperties.Retrieval(ten, 4, 101, 20, 60)).hasMessageContaining("vector-candidates");
        assertThatThrownBy(() -> new DocumentProperties.Retrieval(ten, 4, 20, -1, 60)).hasMessageContaining("lexical-candidates");
        assertThatThrownBy(() -> new DocumentProperties.Retrieval(ten, 4, 20, 101, 60)).hasMessageContaining("lexical-candidates");
        assertThatThrownBy(() -> new DocumentProperties.Retrieval(ten, 4, 20, 20, 0)).hasMessageContaining("rrf-k");
        new DocumentProperties.Retrieval(ten, 4, 20, 0, 60);
    }

    private static DocumentProperties props(DataSize maxFile, int maxPages, int chunkSize, int overlap, String model,
                                            int dimensions) {
        return new DocumentProperties(maxFile, maxPages, 2_000_000, 50_000, DataSize.ofMegabytes(64), 200, 4, 20,
                DataSize.ofMegabytes(256), chunkSize, overlap,
                model, dimensions, INGESTION, RETRIEVAL);
    }
}
