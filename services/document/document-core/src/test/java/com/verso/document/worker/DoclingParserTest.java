package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.config.DoclingProperties;
import com.verso.document.config.DocumentProperties;
import com.verso.document.testing.TestDocx;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.DoclingClient.DoclingException;
import com.verso.document.worker.DoclingParser.Failure;
import com.verso.document.worker.DocumentTextExtractor.Extraction;
import com.verso.document.worker.DocumentTextExtractor.Parser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0032: the sidecar path of {@link DocumentTextExtractor} and {@link DoclingParser} with a scripted sidecar. The
 * JDK parser reads every file first (a hostile file never reaches the sidecar), the sidecar's pages replace its text
 * on success, every sidecar failure keeps the JDK text, a PDF without a text layer is retried or refused, the breaker
 * opens after three failures in a row, and with the sidecar off nothing changes.
 */
class DoclingParserTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Answers convert() from a script and counts the calls. */
    static final class ScriptedClient extends DoclingClient {
        final AtomicInteger calls = new AtomicInteger();
        volatile Supplier<JsonNode> answer;

        ScriptedClient(DoclingProperties properties) {
            super(properties, new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class),
                    new StaticListableBeanFactory().getBeanProvider(HttpClientSettings.class));
        }

        @Override
        JsonNode convert(byte[] file, String format, BooleanSupplier stopping) {
            calls.incrementAndGet();
            return answer.get();
        }
    }

    private final MutableClock clock = new MutableClock();
    private final DocumentProperties limits = TestPdfs.properties();
    private final DoclingProperties on = TestPdfs.docling(true, "http://docling:5001");
    private final ScriptedClient client = new ScriptedClient(on);
    private final DoclingParser parser = new DoclingParser(client, on, limits, clock);
    private final DocumentTextExtractor extractor = new DocumentTextExtractor(new PdfTextExtractor(limits),
            new DocxTextExtractor(limits), new PlainTextExtractor(limits), parser);

    /** A two-page DoclingDocument: a heading and a table on page 1, a paragraph on page 2. */
    static JsonNode twoPages() {
        String texts = String.join(",",
                DoclingDocumentReaderTest.text(0, "section_header", "body", 1, "Hız sınırları"),
                DoclingDocumentReaderTest.text(1, "text", "body", 2, "İkinci sayfa."));
        String table = """
                {"self_ref": "#/tables/0", "prov": [{"page_no": 1}], "children": [],
                 "data": {"grid": [[%s, %s], [%s, %s]]}}""".formatted(
                DoclingDocumentReaderTest.cell("Alan", true), DoclingDocumentReaderTest.cell("Azami hız", true),
                DoclingDocumentReaderTest.cell("Dış saha", false), DoclingDocumentReaderTest.cell("15 km/sa", false));
        return JSON.readTree(DoclingDocumentReaderTest.document(
                "{\"$ref\": \"#/texts/0\"}, {\"$ref\": \"#/tables/0\"}, {\"$ref\": \"#/texts/1\"}", texts, table, ""));
    }

    @Test
    void extract_whenTheSidecarSucceeds_usesItsPagesWithMarkdownTables() {
        client.answer = DoclingParserTest::twoPages;

        Extraction extraction = extractor.extract(DocumentFormat.PDF, TestPdfs.pages("Hiz siniri", "Ikinci sayfa."), () -> false);

        assertThat(extraction.parser()).isEqualTo(Parser.DOCLING);
        assertThat(extraction.fallback()).isNull();
        assertThat(extraction.texts()).containsExactly(
                "Hız sınırları\n\n| Alan | Azami hız |\n| --- | --- |\n| Dış saha | 15 km/sa |", "İkinci sayfa.");
    }

    @Test
    void extract_whenTheSidecarFailsOrDisagrees_keepsTheJdkTextAndNamesTheReason() {
        byte[] pdf = TestPdfs.pages("Bir.", "Iki.");
        List<String> jdk = new PdfTextExtractor(limits).extract(pdf);

        client.answer = () -> { throw new DoclingException(DoclingClient.Failure.UNAVAILABLE); };
        assertThat(extractor.extract(DocumentFormat.PDF, pdf, () -> false))
                .isEqualTo(new Extraction(jdk, Parser.JDK, Failure.UNAVAILABLE));

        // The sidecar saw a different number of pages: citations would point to the wrong page (llm-rules 6.3).
        client.answer = () -> JSON.readTree(DoclingDocumentReaderTest.document("", "", "", "")
                .replace("\"2\": {\"page_no\": 2}", "\"2\": {\"page_no\": 2}, \"3\": {\"page_no\": 3}"));
        assertThat(extractor.extract(DocumentFormat.PDF, pdf, () -> false).fallback()).isEqualTo(Failure.PAGE_MISMATCH);

        // An output the reader does not understand is a fallback too, not a failed document.
        client.answer = () -> JSON.readTree("{\"schema_name\": \"DoclingDocument\", \"body\": {\"children\": 5}, \"pages\": {\"1\": {}, \"2\": {}}}");
        assertThat(extractor.extract(DocumentFormat.PDF, pdf, () -> false).fallback()).isEqualTo(Failure.INVALID_RESPONSE);
    }

    @Test
    void extract_whenTheSidecarPageIsLongerThanTheLimit_keepsTheJdkText() {
        DocumentProperties small = TestPdfs.properties(500, 2_000_000, 60, DataSizes.MB64, 1000, 150);
        DoclingParser strict = new DoclingParser(client, on, small, clock);
        DocumentTextExtractor withSmallPages = new DocumentTextExtractor(new PdfTextExtractor(small),
                new DocxTextExtractor(small), new PlainTextExtractor(small), strict);
        client.answer = DoclingParserTest::twoPages;
        assertThat(withSmallPages.extract(DocumentFormat.PDF, TestPdfs.pages("Hiz siniri", "Ikinci sayfa."), () -> false).fallback())
                .isEqualTo(Failure.TOO_MUCH_TEXT);
    }

    /** OCR is the only way into a PDF without a text layer: a temporary failure retries, a permanent one is NO_TEXT. */
    @Test
    void extract_whenThePdfHasNoTextLayer_retriesOnATemporaryFailureAndRefusesOnAPermanentOne() {
        byte[] scanned = TestPdfs.blank(2);
        client.answer = DoclingParserTest::twoPages;
        assertThat(extractor.extract(DocumentFormat.PDF, scanned, () -> false).parser()).isEqualTo(Parser.DOCLING);

        client.answer = () -> { throw new DoclingException(DoclingClient.Failure.TIMEOUT); };
        assertThatThrownBy(() -> extractor.extract(DocumentFormat.PDF, scanned, () -> false))
                .isInstanceOf(DocumentTextExtractor.SidecarUnavailableException.class);

        client.answer = () -> { throw new DoclingException(DoclingClient.Failure.CONVERSION_FAILED); };
        assertThatThrownBy(() -> extractor.extract(DocumentFormat.PDF, scanned, () -> false))
                .isInstanceOfSatisfying(IngestionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentFailureReason.NO_TEXT));

        client.answer = () -> { throw new DoclingException(DoclingClient.Failure.CANCELLED); };
        assertThatThrownBy(() -> extractor.extract(DocumentFormat.PDF, scanned, () -> true))
                .isInstanceOf(DocumentTextExtractor.ExtractionStoppedException.class);
    }

    /** The JDK parser's limits come first: an encrypted or oversized PDF is refused and never sent anywhere. */
    @Test
    void extract_whenThePdfIsRefusedByTheJdkParser_neverReachesTheSidecar() {
        client.answer = DoclingParserTest::twoPages;
        assertThatThrownBy(() -> extractor.extract(DocumentFormat.PDF, TestPdfs.encrypted("gizli"), () -> false))
                .isInstanceOfSatisfying(IngestionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentFailureReason.ENCRYPTED));
        assertThatThrownBy(() -> extractor.extract(DocumentFormat.PDF, TestPdfs.graphicsStateBomb(1_000), () -> false))
                .isInstanceOfSatisfying(IngestionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentFailureReason.UNSUPPORTED_PDF));
        assertThatThrownBy(() -> extractor.extract(DocumentFormat.PDF, TestPdfs.fakePdf(), () -> false))
                .isInstanceOf(IngestionRejectedException.class);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void parser_afterThreeFailuresInARow_skipsTheSidecarUntilTheCircuitCloses() {
        byte[] pdf = TestPdfs.pages("Bir.", "Iki.");
        client.answer = () -> { throw new DoclingException(DoclingClient.Failure.UNAVAILABLE); };
        for (int i = 0; i < 3; i++) extractor.extract(DocumentFormat.PDF, pdf, () -> false);
        assertThat(client.calls.get()).isEqualTo(3);

        assertThat(extractor.extract(DocumentFormat.PDF, pdf, () -> false).fallback()).isEqualTo(Failure.CIRCUIT_OPEN);
        assertThat(client.calls.get()).as("open: not called").isEqualTo(3);

        clock.advance(Duration.ofMinutes(5));
        client.answer = DoclingParserTest::twoPages;
        assertThat(extractor.extract(DocumentFormat.PDF, pdf, () -> false).parser()).isEqualTo(Parser.DOCLING);
        assertThat(parser.open()).isFalse();
    }

    /** Bulkhead: with the one permit taken, a second document does not wait for the sidecar, it uses the JDK text. */
    @Test
    void parser_whenAConversionIsAlreadyInFlight_answersBusyWithoutCallingTheSidecar() {
        java.util.concurrent.atomic.AtomicReference<DoclingParser.Result> second = new java.util.concurrent.atomic.AtomicReference<>();
        client.answer = () -> {
            // Still inside the first conversion: the permit is held.
            if (second.get() == null) second.set(parser.pdfPages(TestPdfs.pages("a", "b"), 2, () -> false));
            return twoPages();
        };
        DoclingParser.Result first = parser.pdfPages(TestPdfs.pages("a", "b"), 2, () -> false);
        assertThat(first.ok()).isTrue();
        assertThat(second.get().failure()).isEqualTo(Failure.BUSY);
        assertThat(client.calls.get()).isOne();
        assertThat(parser.open()).as("BUSY is not a sidecar failure").isFalse();
    }

    @Test
    void parser_whenAPdfHasMorePagesThanTheSidecarLimit_usesTheJdkParserWithoutAsking() {
        DoclingProperties twoPages = new DoclingProperties(true, java.net.URI.create("http://docling:5001"), false,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(20), 1,
                org.springframework.util.unit.DataSize.ofMegabytes(32), List.of("tur", "eng"), 1, 3, Duration.ofMinutes(5));
        DoclingParser limited = new DoclingParser(client, twoPages, limits, clock);
        client.answer = DoclingParserTest::twoPages;
        assertThat(limited.pdfPages(TestPdfs.pages("a", "b"), 2, () -> false).failure()).isEqualTo(Failure.TOO_MANY_PAGES);
        assertThat(client.calls.get()).isZero();
    }

    /** Off (the default stack): every format goes to its JDK parser exactly as before, the client is never asked. */
    @Test
    void extract_whenTheSidecarIsOff_isTheJdkPathUnchanged() {
        DoclingProperties off = TestPdfs.docling(false, null);
        ScriptedClient unused = new ScriptedClient(off);
        DocumentTextExtractor plain = new DocumentTextExtractor(new PdfTextExtractor(limits), new DocxTextExtractor(limits),
                new PlainTextExtractor(limits), new DoclingParser(unused, off, limits, clock));
        byte[] pdf = TestPdfs.pages("Bir.", "Iki.");
        assertThat(plain.extract(DocumentFormat.PDF, pdf, () -> false))
                .isEqualTo(new Extraction(new PdfTextExtractor(limits).extract(pdf), Parser.JDK, null));
        assertThatThrownBy(() -> plain.extract(DocumentFormat.PDF, TestPdfs.blank(1), () -> false))
                .isInstanceOfSatisfying(IngestionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(DocumentFailureReason.NO_TEXT));
        assertThat(plain.extract(DocumentFormat.DOCX, TestDocx.docx(TestDocx.p("Metin.")), () -> false).parser())
                .isEqualTo(Parser.JDK);
        assertThat(unused.calls.get()).isZero();
    }

    /** DOCX goes to the sidecar only when docx is on; its blocks become sections (ADR-0016). */
    @Test
    void extract_whenDocxIsNotSwitchedOn_staysWithTheJdkParser() {
        client.answer = DoclingParserTest::twoPages;
        assertThat(extractor.extract(DocumentFormat.DOCX, TestDocx.docx(TestDocx.p("Metin.")), () -> false).parser())
                .isEqualTo(Parser.JDK);
        assertThat(client.calls.get()).isZero();

        DoclingProperties docx = new DoclingProperties(true, java.net.URI.create("http://docling:5001"), true,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(20), 100,
                org.springframework.util.unit.DataSize.ofMegabytes(32), List.of("tur", "eng"), 1, 3, Duration.ofMinutes(5));
        DocumentTextExtractor withDocx = new DocumentTextExtractor(new PdfTextExtractor(limits), new DocxTextExtractor(limits),
                new PlainTextExtractor(limits), new DoclingParser(client, docx, limits, clock));
        Extraction extraction = withDocx.extract(DocumentFormat.DOCX, TestDocx.docx(TestDocx.p("Metin.")), () -> false);
        assertThat(extraction.parser()).isEqualTo(Parser.DOCLING);
        assertThat(extraction.texts()).containsExactly(
                "Hız sınırları\n\n| Alan | Azami hız |\n| --- | --- |\n| Dış saha | 15 km/sa |\n\nİkinci sayfa.");
    }

    private static final class DataSizes {
        static final org.springframework.util.unit.DataSize MB64 = org.springframework.util.unit.DataSize.ofMegabytes(64);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-24T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
