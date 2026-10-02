package com.verso.document.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Limits and ingestion settings of the document module (ADR-0011; starting values, llm-rules 6.4). Every limit is
 * validated at startup: a zero or negative value would switch a protection off silently.
 *
 * @param maxFileSize          largest accepted PDF; must not exceed spring.servlet.multipart.max-file-size
 * @param maxPages             a longer PDF fails with TOO_MANY_PAGES
 * @param maxTextChars         more extracted text fails with TOO_MUCH_TEXT (bounds chunks and embedding calls)
 * @param maxDocumentsPerAccount upload quota per account
 * @param parserMemory         heap the PDF parser may use for one document (PDF bombs, ADR-0011)
 * @param chunkSize            characters per chunk (llm-rules 6.4)
 * @param chunkOverlap         characters shared by neighbouring chunks of one page
 * @param embeddingModel       name stored with every chunk (llm-rules 6.1); the Ollama model in use
 * @param embeddingDimensions  expected vector length; the column is vector(1024)
 * @param ingestion            worker settings
 */
@ConfigurationProperties("verso.document")
public record DocumentProperties(
        @DefaultValue("20MB") DataSize maxFileSize,
        @DefaultValue("500") int maxPages,
        @DefaultValue("2000000") int maxTextChars,
        @DefaultValue("200") int maxDocumentsPerAccount,
        @DefaultValue("256MB") DataSize parserMemory,
        @DefaultValue("1000") int chunkSize,
        @DefaultValue("150") int chunkOverlap,
        String embeddingModel,
        @DefaultValue("1024") int embeddingDimensions,
        @DefaultValue Ingestion ingestion) {

    /**
     * @param enabled          the scheduled poll; tests switch it off and drive the worker directly
     * @param pollIntervalMs   pause between polls
     * @param lease            claim lease; renewed after every embedding batch (reference 11.1)
     * @param maxAttempts      claims per document before PROCESSING_FAILED, crashes included (poison PDFs)
     * @param retryBackoff     first retry delay, doubled per attempt
     * @param maxRetryBackoff  upper bound of the retry delay
     * @param embeddingBatch   chunks per embedding request
     * @param maxDocumentsPerPoll documents one poll processes before yielding
     */
    public record Ingestion(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5000") long pollIntervalMs,
            @DefaultValue("10m") Duration lease,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("30s") Duration retryBackoff,
            @DefaultValue("10m") Duration maxRetryBackoff,
            @DefaultValue("16") int embeddingBatch,
            @DefaultValue("10") int maxDocumentsPerPoll) {

        public Ingestion {
            positive(pollIntervalMs, "ingestion.poll-interval-ms");
            positive(maxAttempts, "ingestion.max-attempts");
            positive(embeddingBatch, "ingestion.embedding-batch");
            positive(maxDocumentsPerPoll, "ingestion.max-documents-per-poll");
            if (!lease.isPositive() || !retryBackoff.isPositive() || maxRetryBackoff.compareTo(retryBackoff) < 0) {
                throw new IllegalStateException("verso.document.ingestion: lease and backoffs must be positive, max >= first");
            }
        }
    }

    public DocumentProperties {
        if (maxFileSize.toBytes() <= 0) throw new IllegalStateException("verso.document.max-file-size must be positive");
        if (parserMemory.toBytes() <= 0) throw new IllegalStateException("verso.document.parser-memory must be positive");
        positive(maxPages, "max-pages");
        positive(maxTextChars, "max-text-chars");
        positive(maxDocumentsPerAccount, "max-documents-per-account");
        positive(chunkSize, "chunk-size");
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalStateException("verso.document.chunk-overlap must be at least 0 and below chunk-size");
        }
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalStateException("verso.document.embedding-model is required");
        }
        if (embeddingDimensions != 1024) {
            // The column type fixes the length; another model is a migration and a re-index (llm-rules 6.2).
            throw new IllegalStateException("verso.document.embedding-dimensions must be 1024 (column vector(1024))");
        }
    }

    private static void positive(long value, String name) {
        if (value <= 0) throw new IllegalStateException("verso.document." + name + " must be positive");
    }
}
