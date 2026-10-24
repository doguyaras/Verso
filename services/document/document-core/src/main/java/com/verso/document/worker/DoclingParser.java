package com.verso.document.worker;

import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.config.DoclingProperties;
import com.verso.document.config.DocumentProperties;
import com.verso.document.worker.DoclingClient.DoclingException;
import com.verso.document.worker.DoclingDocumentReader.Block;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.verso.document.config.ConditionalOnIngestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Structured parsing through the docling-serve sidecar (ADR-0032), guarded like every remote call (AGENTS.md 5): a
 * bulkhead (documents in flight per instance), a circuit breaker (after circuit-failures failures in a row the sidecar
 * is skipped for circuit-open) and the client's timeouts. It never throws for a sidecar problem: the result names the
 * failure and {@link DocumentTextExtractor} falls back to the JDK parser. The breaker's state is per instance on
 * purpose, like the worker's model pause: each instance judges its own connection to the sidecar.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnIngestion
public class DoclingParser {

    /** Why the sidecar's result was not used. Fixed values: they reach logs and a metric tag. */
    public enum Failure {
        TOO_MANY_PAGES, CIRCUIT_OPEN, BUSY, UNAVAILABLE, TIMEOUT, CONVERSION_FAILED, INVALID_RESPONSE, TOO_LARGE,
        PAGE_MISMATCH, TOO_MUCH_TEXT, NO_TEXT, CANCELLED;

        /** The sidecar may well succeed later: a document without a text layer is retried, not failed. */
        boolean temporary() {
            return this == CIRCUIT_OPEN || this == BUSY || this == UNAVAILABLE || this == TIMEOUT;
        }
    }

    /** Pages (PDF) or sections (DOCX) on success, otherwise the failure. */
    public record Result(List<String> texts, Failure failure) {
        static Result failed(Failure failure) {
            return new Result(null, failure);
        }

        boolean ok() {
            return texts != null;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(DoclingParser.class);

    private final DoclingClient client;
    private final DoclingProperties properties;
    private final DocumentProperties limits;
    private final Clock clock;
    private final Semaphore inFlight;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile Instant openUntil = Instant.MIN;

    public DoclingParser(DoclingClient client, DoclingProperties properties, DocumentProperties limits, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.limits = limits;
        this.clock = clock;
        this.inFlight = new Semaphore(properties.concurrency());
    }

    boolean handles(DocumentFormat format) {
        return properties.enabled() && switch (format) {
            case PDF -> true;
            case DOCX -> properties.docx();
            default -> false;
        };
    }

    /** One text per PDF page; {@code pages} is the page count the JDK parser found, the sidecar must agree. */
    Result pdfPages(byte[] pdf, int pages, BooleanSupplier stopping) {
        if (pages > properties.maxPages()) return Result.failed(Failure.TOO_MANY_PAGES);
        return guarded(() -> {
            JsonNode document = client.convert(pdf, "pdf", stopping);
            if (DoclingDocumentReader.pageCount(document) != pages) return Result.failed(Failure.PAGE_MISMATCH);
            List<StringBuilder> texts = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) texts.add(new StringBuilder());
            for (Block block : DoclingDocumentReader.read(document)) {
                if (block.page() < 1 || block.page() > pages) return Result.failed(Failure.PAGE_MISMATCH);
                StringBuilder page = texts.get(block.page() - 1);
                if (!page.isEmpty()) page.append("\n\n");
                page.append(block.text());
            }
            List<String> result = new ArrayList<>(pages);
            long total = 0;
            for (StringBuilder text : texts) {
                String page = TextNormalizer.normalize(text.toString());
                // Markdown tables are longer than the JDK's text: the same limits apply to what is stored.
                if (page.length() > limits.maxPageChars()) return Result.failed(Failure.TOO_MUCH_TEXT);
                total += page.length();
                result.add(page);
            }
            if (total > limits.maxTextChars()) return Result.failed(Failure.TOO_MUCH_TEXT);
            if (total == 0) return Result.failed(Failure.NO_TEXT);
            return new Result(result, null);
        });
    }

    /** Sections of a DOCX (ADR-0016): headings start a section, a table is one block. */
    Result docxSections(byte[] docx, BooleanSupplier stopping) {
        return guarded(() -> {
            JsonNode document = client.convert(docx, "docx", stopping);
            List<TextSections.Block> blocks = new ArrayList<>();
            for (Block block : DoclingDocumentReader.read(document)) {
                blocks.add(new TextSections.Block(block.text(), block.heading()));
            }
            try {
                return new Result(TextSections.split(blocks, limits), null);
            } catch (IngestionRejectedException e) {
                return Result.failed(switch (e.reason()) {
                    case NO_TEXT -> Failure.NO_TEXT;
                    default -> Failure.TOO_MUCH_TEXT;
                });
            }
        });
    }

    private interface Conversion {
        Result run();
    }

    private Result guarded(Conversion conversion) {
        if (clock.instant().isBefore(openUntil)) return Result.failed(Failure.CIRCUIT_OPEN);
        if (!inFlight.tryAcquire()) return Result.failed(Failure.BUSY);
        try {
            Result result = conversion.run();
            failures.set(0);
            return result;
        } catch (DoclingException e) {
            Failure failure = Failure.valueOf(e.failure().name());
            if (failure != Failure.CANCELLED) recordFailure(failure);
            return Result.failed(failure);
        } catch (RuntimeException e) {
            // An output this reader does not expect must not fail the document: the JDK parser still can read it.
            log.warn("Docling result unreadable: exceptionType={}", e.getClass().getSimpleName());
            recordFailure(Failure.INVALID_RESPONSE);
            return Result.failed(Failure.INVALID_RESPONSE);
        } finally {
            inFlight.release();
        }
    }

    private void recordFailure(Failure failure) {
        int count = failures.incrementAndGet();
        if (count >= properties.circuitFailures()) {
            failures.set(0);
            openUntil = clock.instant().plus(properties.circuitOpen());
            log.warn("Docling sidecar skipped: reason={} consecutiveFailures={} openSeconds={}", failure, count,
                    properties.circuitOpen().toSeconds());
        }
    }

    /** True while the breaker skips the sidecar; for tests. */
    boolean open() {
        return clock.instant().isBefore(openUntil);
    }
}
