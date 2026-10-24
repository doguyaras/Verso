package com.verso.document.worker;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.worker.DoclingParser.Failure;
import com.verso.document.worker.DoclingParser.Result;
import java.util.List;
import java.util.function.BooleanSupplier;
import com.verso.document.config.ConditionalOnIngestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The text of an uploaded file, one string per page (PDF) or section (DOCX, TXT, MD; ADR-0016). The format was decided
 * at upload and stored with the document; every parser ends in a fixed failure reason, never in its own message.
 *
 * <p>With the docling sidecar on (ADR-0032), PDFs (and DOCX when configured) are still read by the JDK parser first:
 * its limits refuse a hostile file before it reaches the sidecar, and its text is the fallback. The sidecar's
 * structured text (Markdown tables, OCR of pages without a text layer) replaces it when the conversion succeeds; any
 * sidecar failure keeps the JDK text. A PDF without any text layer has no fallback: a temporary sidecar failure is
 * retried later ({@link SidecarUnavailableException}), any other one ends in NO_TEXT as before.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnIngestion
public class DocumentTextExtractor {

    /** Which parser produced the stored text. */
    public enum Parser { JDK, DOCLING }

    /**
     * The texts and where they came from; {@code fallback} names the sidecar failure when the JDK text was kept
     * although the sidecar was asked, otherwise null.
     */
    public record Extraction(List<String> texts, Parser parser, Failure fallback) {
    }

    /** The sidecar was temporarily unavailable for a file only it can read; the worker retries with backoff. */
    static final class SidecarUnavailableException extends RuntimeException {
        SidecarUnavailableException(Failure failure) {
            super(failure.name(), null, false, false);
        }
    }

    /** Shutdown began while the sidecar converted; the worker hands the document back without spending an attempt. */
    static final class ExtractionStoppedException extends RuntimeException {
        ExtractionStoppedException() {
            super("stopping", null, false, false);
        }
    }

    private final PdfTextExtractor pdf;
    private final DocxTextExtractor docx;
    private final PlainTextExtractor plain;
    private final DoclingParser docling;

    public DocumentTextExtractor(PdfTextExtractor pdf, DocxTextExtractor docx, PlainTextExtractor plain,
                                 DoclingParser docling) {
        this.pdf = pdf;
        this.docx = docx;
        this.plain = plain;
        this.docling = docling;
    }

    public List<String> extract(DocumentFormat format, byte[] content) {
        return extract(format, content, () -> false).texts();
    }

    public Extraction extract(DocumentFormat format, byte[] content, BooleanSupplier stopping) {
        if (docling.handles(format)) {
            return format == DocumentFormat.PDF ? pdfWithSidecar(content, stopping) : docxWithSidecar(content, stopping);
        }
        List<String> texts = switch (format) {
            case PDF -> pdf.extract(content);
            case DOCX -> docx.extract(content);
            case TXT -> plain.extract(content, false);
            case MD -> plain.extract(content, true);
        };
        return new Extraction(texts, Parser.JDK, null);
    }

    private Extraction pdfWithSidecar(byte[] content, BooleanSupplier stopping) {
        List<String> jdk = pdf.read(content);
        boolean jdkText = jdk.stream().anyMatch(page -> !page.isEmpty());
        Result result = docling.pdfPages(content, jdk.size(), stopping);
        if (result.ok()) return new Extraction(result.texts(), Parser.DOCLING, null);
        if (result.failure() == Failure.CANCELLED) throw new ExtractionStoppedException();
        if (!jdkText) {
            if (result.failure().temporary()) throw new SidecarUnavailableException(result.failure());
            throw new IngestionRejectedException(result.failure() == Failure.TOO_MUCH_TEXT
                    ? DocumentFailureReason.TOO_MUCH_TEXT : DocumentFailureReason.NO_TEXT);
        }
        return new Extraction(jdk, Parser.JDK, result.failure());
    }

    private Extraction docxWithSidecar(byte[] content, BooleanSupplier stopping) {
        List<String> jdk = docx.extract(content);
        Result result = docling.docxSections(content, stopping);
        if (result.ok()) return new Extraction(result.texts(), Parser.DOCLING, null);
        if (result.failure() == Failure.CANCELLED) throw new ExtractionStoppedException();
        return new Extraction(jdk, Parser.JDK, result.failure());
    }

    /** The reason when the stored file is missing: the bytes were never a readable file of this format. */
    static DocumentFailureReason unreadable(DocumentFormat format) {
        return format == DocumentFormat.PDF ? DocumentFailureReason.NOT_A_PDF : DocumentFailureReason.INVALID_FILE;
    }
}
