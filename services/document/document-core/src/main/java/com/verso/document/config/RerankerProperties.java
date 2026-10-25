package com.verso.document.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The optional cross-encoder reranker (ADR-0043): a HF TEI sidecar serving bge-reranker-v2-m3 reorders the fused
 * candidates of a question before the top-k are kept. Off by default; when off, nothing here is used and retrieval is
 * exactly the hybrid search of ADR-0022. Every failure falls back to the fused order. Validated at startup, like
 * {@link DoclingProperties}.
 *
 * @param enabled          rerank the fused candidates; meant for GPU installs (the CPU latency is in ADR-0043)
 * @param baseUrl          the sidecar on the internal network (compose: http://reranker:80); required when enabled
 * @param candidates       fused candidates sent to the reranker (top-N); the best top-k of them are kept
 * @param connectTimeout   TCP connect timeout
 * @param timeout          the whole rerank call, queueing in the sidecar included; after it the fused order is used
 * @param maxPassageChars  characters of one passage sent (the model reads ~512 tokens; the rest is cut anyway)
 * @param concurrency      rerank calls in flight per instance (bulkhead); a question over it keeps the fused order
 * @param circuitFailures  consecutive failures after which the reranker is skipped for circuitOpen
 * @param circuitOpen      how long the reranker is skipped after circuitFailures
 */
@ConfigurationProperties("verso.document.reranker")
public record RerankerProperties(
        @DefaultValue("false") boolean enabled,
        URI baseUrl,
        @DefaultValue("20") int candidates,
        @DefaultValue("500ms") Duration connectTimeout,
        @DefaultValue("2s") Duration timeout,
        @DefaultValue("2000") int maxPassageChars,
        @DefaultValue("4") int concurrency,
        @DefaultValue("3") int circuitFailures,
        @DefaultValue("1m") Duration circuitOpen) {

    public RerankerProperties {
        if (enabled) {
            if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                    || baseUrl.getHost() == null) {
                throw new IllegalStateException("verso.document.reranker.base-url must be an http(s) URL when enabled");
            }
            if (baseUrl.getUserInfo() != null || baseUrl.getQuery() != null) {
                throw new IllegalStateException("verso.document.reranker.base-url must not carry credentials or a query");
            }
        }
        // Upper bound: one question's rerank work stays bounded (hot path, ADR-0008); the fused list has at most
        // vector-candidates + lexical-candidates entries anyway.
        if (candidates < 2 || candidates > 100) throw new IllegalStateException("verso.document.reranker.candidates must be 2..100");
        positive(connectTimeout, "connect-timeout");
        positive(timeout, "timeout");
        positive(circuitOpen, "circuit-open");
        if (maxPassageChars < 100) throw new IllegalStateException("verso.document.reranker.max-passage-chars must be at least 100");
        if (concurrency < 1) throw new IllegalStateException("verso.document.reranker.concurrency must be positive");
        if (circuitFailures < 1) throw new IllegalStateException("verso.document.reranker.circuit-failures must be positive");
    }

    private static void positive(Duration value, String name) {
        if (value == null || !value.isPositive()) {
            throw new IllegalStateException("verso.document.reranker." + name + " must be positive");
        }
    }
}
