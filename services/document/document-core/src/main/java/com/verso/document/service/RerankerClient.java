package com.verso.document.service;

import com.verso.document.config.RerankerProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP client of the reranker sidecar (ADR-0043): HF TEI's {@code POST /rerank} with the question and the candidate
 * texts; the answer is the candidates' indexes, best first. The connection may only go to an internal address (the
 * sidecar's network has no gateway; this is the second fence, llm-rules 1.1), never follows a redirect and reads a
 * bounded body. Failures carry a fixed kind, never the sidecar's message or a passage's text (llm-rules 2.1).
 */
@Component
// Web application only, like retrieval itself (DocumentRetrievalServiceImpl).
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RerankerClient {

    /** Why a rerank produced no order. Fixed values: they reach logs and a metric tag. */
    enum Failure { UNAVAILABLE, TIMEOUT, INVALID_RESPONSE }

    /** A rerank that did not succeed; no cause and no message: both could carry passage text. */
    static final class RerankException extends RuntimeException {
        private final Failure failure;

        RerankException(Failure failure) {
            super(failure.name(), null, false, false);
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }

    /** {@code [{"index": 3, "score": 0.97}, …]} for at most 100 candidates is a few kilobytes. */
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final RestClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    public RerankerClient(RerankerProperties properties, ObjectProvider<RestClient.Builder> builders,
                          ObjectProvider<HttpClientSettings> settings) {
        if (!properties.enabled()) {
            this.client = null;
            return;
        }
        HttpClientSettings own = settings.getIfAvailable(HttpClientSettings::defaults)
                .withTimeouts(properties.connectTimeout(), properties.timeout())
                .withRedirects(HttpRedirects.DONT_FOLLOW)
                .withInetAddressFilter(InetAddressFilter.internalAddresses());
        this.client = builders.getIfAvailable(RestClient::builder)
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(own))
                .build();
    }

    /** The configured HTTP client, for the egress tests (llm-rules 1.1). */
    RestClient restClient() {
        return client;
    }

    /**
     * The indexes of {@code texts}, most relevant first: a permutation of 0..n-1, checked, so a broken answer can never
     * drop, repeat or invent a passage.
     */
    List<Integer> rank(String query, List<String> texts) {
        return ranked(query, texts).stream().map(Ranked::index).toList();
    }

    /**
     * ADR-0060: the relevance of each of {@code texts} to {@code query}, by index, each a sigmoid score in 0..1. A score
     * that is missing, not finite or outside 0..1 makes the whole answer invalid: a caller compares it with a threshold.
     */
    double[] scores(String query, List<String> texts) {
        double[] scores = new double[texts.size()];
        for (Ranked item : ranked(query, texts)) {
            double score = item.score();
            if (!(score >= 0 && score <= 1)) throw new RerankException(Failure.INVALID_RESPONSE);
            scores[item.index()] = score;
        }
        return scores;
    }

    /** One item of the answer: a candidate's index and its score (NaN when the answer has none). */
    private record Ranked(int index, double score) {
    }

    private List<Ranked> ranked(String query, List<String> texts) {
        if (client == null) throw new IllegalStateException("the reranker is disabled");
        // truncate: TEI cuts a pair longer than the model's window instead of failing it; raw_scores false: sigmoid.
        Map<String, Object> body = Map.of("query", query, "texts", texts, "truncate", true, "raw_scores", false,
                "return_text", false);
        JsonNode answer;
        try {
            answer = client.post().uri("/rerank").contentType(MediaType.APPLICATION_JSON).body(body)
                    .exchange((request, response) -> read(response.getStatusCode().value(), response.getBody()));
        } catch (RuntimeException e) {
            throw map(e);
        }
        if (answer == null || !answer.isArray() || answer.size() != texts.size()) {
            throw new RerankException(Failure.INVALID_RESPONSE);
        }
        boolean[] seen = new boolean[texts.size()];
        List<Ranked> order = new ArrayList<>(texts.size());
        for (JsonNode item : answer) {
            JsonNode index = item.path("index");
            if (!index.isIntegralNumber()) throw new RerankException(Failure.INVALID_RESPONSE);
            int i = index.asInt();
            if (i < 0 || i >= seen.length || seen[i]) throw new RerankException(Failure.INVALID_RESPONSE);
            seen[i] = true;
            JsonNode score = item.path("score");
            order.add(new Ranked(i, score.isNumber() ? score.asDouble() : Double.NaN));
        }
        return order;
    }

    private JsonNode read(int status, InputStream body) throws IOException {
        if (status >= 500 || status == 429) throw new RerankException(Failure.UNAVAILABLE);
        if (status != 200) throw new RerankException(Failure.INVALID_RESPONSE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = body.read(buffer)) != -1) {
            total += read;
            if (total > MAX_RESPONSE_BYTES) throw new RerankException(Failure.INVALID_RESPONSE);
            out.write(buffer, 0, read);
        }
        try {
            return json.readTree(out.toByteArray());
        } catch (JacksonException e) {
            throw new RerankException(Failure.INVALID_RESPONSE);
        }
    }

    /** Transport failures as a fixed kind; the client may wrap what the response handler threw. */
    private static RerankException map(RuntimeException e) {
        Throwable cause = e;
        for (int i = 0; i < 20 && cause != null; i++, cause = cause.getCause()) {
            if (cause instanceof RerankException own) return own;
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return new RerankException(Failure.TIMEOUT);
            }
        }
        // Refused or reset connection, unknown host, an address the filter refuses (FilteredHostException).
        return new RerankException(Failure.UNAVAILABLE);
    }
}
