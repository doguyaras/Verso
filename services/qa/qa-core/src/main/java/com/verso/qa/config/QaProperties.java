package com.verso.qa.config;

import com.verso.document.api.DocumentRetrieval;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Retrieval and answer limits (llm-rules 3.4, 3.5; ADR-0008, ADR-0022). Starting values, measured by the eval set
 * (phase 8; set version 2, eval/README.md "Eşikler ve karar").
 *
 * @param topK             passages retrieved per question
 * @param minSimilarity    cosine similarity at which the best passage alone makes a question answerable (3.4, ADR-0022)
 * @param lexicalMinSimilarity below minSimilarity, the floor at which lexical evidence can still make it answerable:
 *                         the most similar passage names a specific question term. Below this floor the model is not
 *                         asked whatever the terms say
 * @param passageMinSimilarity once the gate is open, the prompt gets the most similar passage and every other passage
 *                         at or above this similarity; weaker ones stay out
 * @param specificTermMaxShare a question term in at most this share of the account's chunks counts as specific (a
 *                         code, an abbreviation); 0 turns the lexical branch off. Must stay below
 *                         DocumentRetrieval.TERM_SHARE_LIMIT, above which shares are not counted
 * @param maxPassageChars  characters of one passage in the prompt
 * @param chatConcurrency  chat calls in flight per instance (ADR-0008); a CPU model answers one at a time anyway
 * @param chatWait         how long a question waits for a free chat slot before 503 MODEL_BUSY
 * @param circuitFailures  consecutive chat failures that open the circuit (ADR-0008)
 * @param circuitOpen      how long an open circuit answers 503 MODEL_UNAVAILABLE without asking the model
 * @param localChatTimeout one chat call in local mode, whatever the client says (ADR-0008: 90 s)
 * @param cloudChatTimeout one chat call in cloud mode (ADR-0008: 30 s)
 * @param firstTokenTimeout a streamed answer whose model has sent no token yet is given up after this (ADR-0062): a
 *                         stalled local runner is cut before the whole chat timeout and its slot is freed sooner. The
 *                         shorter of this and the chat timeout applies before the first token; the chat timeout after
 */
@ConfigurationProperties("verso.qa")
public record QaProperties(
        @DefaultValue("5") int topK,
        @DefaultValue("0.47") double minSimilarity,
        @DefaultValue("0.44") double lexicalMinSimilarity,
        @DefaultValue("0.045") double specificTermMaxShare,
        @DefaultValue("0.50") double passageMinSimilarity,
        @DefaultValue("1500") int maxPassageChars,
        @DefaultValue("1") int chatConcurrency,
        @DefaultValue("5s") Duration chatWait,
        @DefaultValue("2") int circuitFailures,
        @DefaultValue("15s") Duration circuitOpen,
        @DefaultValue("90s") Duration localChatTimeout,
        @DefaultValue("30s") Duration cloudChatTimeout,
        @DefaultValue("60s") Duration firstTokenTimeout) {

    /** Without a first-token bound of its own: the chat timeout alone bounds the first token too. */
    public QaProperties(int topK, double minSimilarity, double lexicalMinSimilarity, double specificTermMaxShare,
                        double passageMinSimilarity, int maxPassageChars, int chatConcurrency, Duration chatWait,
                        int circuitFailures, Duration circuitOpen, Duration localChatTimeout, Duration cloudChatTimeout) {
        this(topK, minSimilarity, lexicalMinSimilarity, specificTermMaxShare, passageMinSimilarity, maxPassageChars,
                chatConcurrency, chatWait, circuitFailures, circuitOpen, localChatTimeout, cloudChatTimeout,
                localChatTimeout.compareTo(cloudChatTimeout) >= 0 ? localChatTimeout : cloudChatTimeout);
    }

    @ConstructorBinding
    public QaProperties {
        if (topK < 1 || topK > 20) throw new IllegalStateException("verso.qa.top-k must be between 1 and 20");
        if (minSimilarity < 0 || minSimilarity > 1) throw new IllegalStateException("verso.qa.min-similarity must be 0..1");
        if (lexicalMinSimilarity < 0 || lexicalMinSimilarity > minSimilarity) {
            throw new IllegalStateException("verso.qa.lexical-min-similarity must be 0..min-similarity");
        }
        if (passageMinSimilarity < 0 || passageMinSimilarity > 1) {
            throw new IllegalStateException("verso.qa.passage-min-similarity must be 0..1");
        }
        if (specificTermMaxShare < 0 || specificTermMaxShare >= DocumentRetrieval.TERM_SHARE_LIMIT) {
            throw new IllegalStateException("verso.qa.specific-term-max-share must be at least 0 and below "
                    + DocumentRetrieval.TERM_SHARE_LIMIT);
        }
        if (maxPassageChars < 100) throw new IllegalStateException("verso.qa.max-passage-chars must be at least 100");
        if (chatConcurrency < 1) throw new IllegalStateException("verso.qa.chat-concurrency must be positive");
        if (chatWait.isNegative()) throw new IllegalStateException("verso.qa.chat-wait must not be negative");
        if (circuitFailures < 1) throw new IllegalStateException("verso.qa.circuit-failures must be positive");
        if (!circuitOpen.isPositive()) throw new IllegalStateException("verso.qa.circuit-open must be positive");
        if (!localChatTimeout.isPositive() || !cloudChatTimeout.isPositive()) {
            throw new IllegalStateException("verso.qa.*-chat-timeout must be positive");
        }
        if (firstTokenTimeout == null || !firstTokenTimeout.isPositive()) {
            throw new IllegalStateException("verso.qa.first-token-timeout must be positive");
        }
    }
}
