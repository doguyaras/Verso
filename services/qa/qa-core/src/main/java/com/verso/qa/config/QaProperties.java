package com.verso.qa.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retrieval and answer limits (llm-rules 3.4, 3.5; ADR-0008). Starting values, measured by the eval set (phase 8).
 *
 * @param topK             passages retrieved per question
 * @param minSimilarity    cosine similarity the best passage must reach; below it the model is not asked (3.4)
 * @param maxPassageChars  characters of one passage in the prompt
 * @param maxAnswerTokens  tokens the model may generate
 * @param temperature      sampling temperature; low, answers should follow the passages
 * @param chatConcurrency  chat calls in flight per instance (ADR-0008); a CPU model answers one at a time anyway
 * @param chatWait         how long a question waits for a free chat slot before 503 MODEL_BUSY
 */
@ConfigurationProperties("verso.qa")
public record QaProperties(
        @DefaultValue("5") int topK,
        @DefaultValue("0.45") double minSimilarity,
        @DefaultValue("1500") int maxPassageChars,
        @DefaultValue("512") int maxAnswerTokens,
        @DefaultValue("0.1") double temperature,
        @DefaultValue("2") int chatConcurrency,
        @DefaultValue("5s") Duration chatWait) {

    public QaProperties {
        if (topK < 1 || topK > 20) throw new IllegalStateException("verso.qa.top-k must be between 1 and 20");
        if (minSimilarity < 0 || minSimilarity > 1) throw new IllegalStateException("verso.qa.min-similarity must be 0..1");
        if (maxPassageChars < 100) throw new IllegalStateException("verso.qa.max-passage-chars must be at least 100");
        if (maxAnswerTokens < 16) throw new IllegalStateException("verso.qa.max-answer-tokens must be at least 16");
        if (chatConcurrency < 1) throw new IllegalStateException("verso.qa.chat-concurrency must be positive");
        if (chatWait.isNegative()) throw new IllegalStateException("verso.qa.chat-wait must not be negative");
    }
}
