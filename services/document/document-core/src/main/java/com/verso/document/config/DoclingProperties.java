package com.verso.document.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The optional docling-serve sidecar (ADR-0032): layout- and table-aware parsing with OCR. Off by default; when off,
 * nothing here is used and the JDK parsers work exactly as before (ADR-0011, ADR-0016). Every limit is validated at
 * startup, like {@link DocumentProperties}.
 *
 * @param enabled            send documents to the sidecar; every failure falls back to the JDK parser
 * @param baseUrl            the sidecar on the internal network (compose: http://docling:5001); required when enabled
 * @param docx               also send DOCX files (PDF always); off by default: measured on PDF only (ADR-0032)
 * @param connectTimeout     TCP connect timeout of every call
 * @param readTimeout        read timeout of one HTTP call (submit, one status poll, result download)
 * @param documentTimeout    the whole conversion of one document, queue and polling included; must stay below the
 *                           ingestion lease, so a slow conversion cannot hand the document to a second worker
 * @param pollInterval       pause between two status polls
 * @param maxPages           longer PDFs skip the sidecar and use the JDK parser (CPU time of the sidecar)
 * @param maxResponseSize    largest result body read; a larger one is a failure (memory of the API's JVM)
 * @param ocrLanguages       Tesseract language codes, in order of preference
 * @param concurrency        documents in flight to the sidecar per instance (bulkhead)
 * @param circuitFailures    consecutive failures after which the sidecar is skipped for circuitOpen
 * @param circuitOpen        how long the sidecar is skipped after circuitFailures
 */
@ConfigurationProperties("verso.document.docling")
public record DoclingProperties(
        @DefaultValue("false") boolean enabled,
        URI baseUrl,
        @DefaultValue("false") boolean docx,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout,
        @DefaultValue("5m") Duration documentTimeout,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("100") int maxPages,
        @DefaultValue("16MB") DataSize maxResponseSize,
        @DefaultValue({"tur", "eng"}) List<String> ocrLanguages,
        @DefaultValue("1") int concurrency,
        @DefaultValue("3") int circuitFailures,
        @DefaultValue("5m") Duration circuitOpen) {

    /** Tesseract's own codes ("tur", "eng"): docling-serve 1.38 rejects the BCP-47 forms for Tesseract (ADR-0032). */
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{3}(_[a-z]+)?");

    public DoclingProperties {
        ocrLanguages = ocrLanguages == null ? List.of() : List.copyOf(ocrLanguages);
        if (enabled) {
            if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                    || baseUrl.getHost() == null) {
                throw new IllegalStateException("verso.document.docling.base-url must be an http(s) URL when enabled");
            }
            if (baseUrl.getUserInfo() != null || baseUrl.getQuery() != null) {
                throw new IllegalStateException("verso.document.docling.base-url must not carry credentials or a query");
            }
        }
        positive(connectTimeout, "connect-timeout");
        positive(readTimeout, "read-timeout");
        positive(documentTimeout, "document-timeout");
        positive(pollInterval, "poll-interval");
        positive(circuitOpen, "circuit-open");
        if (maxPages < 1) throw new IllegalStateException("verso.document.docling.max-pages must be positive");
        if (maxResponseSize.toBytes() <= 0) throw new IllegalStateException("verso.document.docling.max-response-size must be positive");
        if (concurrency < 1) throw new IllegalStateException("verso.document.docling.concurrency must be positive");
        if (circuitFailures < 1) throw new IllegalStateException("verso.document.docling.circuit-failures must be positive");
        if (ocrLanguages.isEmpty() || !ocrLanguages.stream().allMatch(l -> LANGUAGE.matcher(l).matches())) {
            throw new IllegalStateException("verso.document.docling.ocr-languages must be Tesseract codes such as tur, eng");
        }
    }

    private static void positive(Duration value, String name) {
        if (value == null || !value.isPositive()) {
            throw new IllegalStateException("verso.document.docling." + name + " must be positive");
        }
    }
}
