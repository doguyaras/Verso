package com.verso.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestDocx;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.config.QaProperties;
import com.verso.support.TestChatModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoPostgres;
import com.verso.support.VersoTestEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0016 (llm-rules 7.1): does retrieval work as well on files without pages? The six synthetic sample documents
 * (samples/src) are ingested three times, each into its own account: as the committed PDFs, as DOCX (headings in a
 * "Başlık 1" style) and as Markdown ("##" headings). Every answerable question of eval/eval-set.json is searched in
 * each account. Pages and sections do not line up, so instead of recall of a page this measures, per format:
 * the expected document among the top 5, the expected fact (as a whole word or number) in the top-5 passages, and how
 * the best similarity sits against the threshold. Writes eval/results/retrieval-formats.json. Tag "eval", like
 * {@link RetrievalEvalTest}; needs Ollama with bge-m3:567m.
 *
 * <pre>./mvnw -pl verso-app -am test -Dverso.excludedGroups=none -Dgroups=eval -Dtest=FormatEvalTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false</pre>
 */
@Tag("eval")
@SpringBootTest(properties = {
        "spring.ai.ollama.base-url=${VERSO_EVAL_OLLAMA_URL:http://localhost:11434}",
        "spring.ai.ollama.embedding.model=bge-m3:567m",
        "spring.http.clients.read-timeout=120s"})
// Not @VersoTestEnvironment: that one replaces the embedding model; here the real one embeds.
@ContextConfiguration(initializers = {VersoPostgres.Initializer.class, TestIdp.Initializer.class,
        VersoTestEnvironment.Models.class})
@Import(TestChatModel.Config.class)
class FormatEvalTest {

    private static final Path ROOT = VersoPostgres.repoRoot();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern HEADING = Pattern.compile("\\d+\\. .{1,60}");
    private static final Pattern MARKER = Pattern.compile("\\[\\d+]");

    @Autowired
    DocumentRetrieval retrieval;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    QaProperties qa;

    @Test
    void evalSet_whenTheSamplesAreDocxOrMarkdown_retrievesAsWellAsFromPdf() throws Exception {
        jdbc.update("DELETE FROM document.document");
        JsonNode set = JSON.readTree(Files.readString(ROOT.resolve("eval/eval-set.json"), StandardCharsets.UTF_8));
        Map<String, Object> summaries = new LinkedHashMap<>();
        Map<String, Object> rows = new LinkedHashMap<>();
        for (DocumentFormat format : new DocumentFormat[] {DocumentFormat.PDF, DocumentFormat.DOCX, DocumentFormat.MD}) {
            String account = "acct-format-eval-" + format + "-" + UUID.randomUUID();
            ingest(account, format);
            List<Map<String, Object>> perQuestion = new ArrayList<>();
            int answerable = 0, docHit = 0, factHit = 0, above = 0, unanswerable = 0, below = 0;
            for (JsonNode q : set.get("questions")) {
                List<RetrievedPassage> found = retrieval.search(account, q.get("question").asString(), 5);
                double best = found.isEmpty() ? 0 : found.getFirst().similarity();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", q.get("id").asString());
                row.put("bestSimilarity", round(best));
                if (q.get("answerable").asBoolean()) {
                    answerable++;
                    String stem = stem(q.get("document").asString());
                    boolean doc = found.stream().anyMatch(p -> stem(p.fileName()).equals(stem));
                    String passages = String.join("\n", found.stream().map(RetrievedPassage::content).toList());
                    boolean fact = Stream.of(q.get("expect").asString().split("\\|")).anyMatch(e -> containsFact(passages, e));
                    if (doc) docHit++;
                    if (fact) factHit++;
                    if (best >= qa.minSimilarity()) above++;
                    row.put("documentInTop5", doc);
                    row.put("factInTop5", fact);
                } else {
                    unanswerable++;
                    if (best < qa.minSimilarity()) below++;
                }
                perQuestion.add(row);
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("documentHitAt5", ratio(docHit, answerable));
            summary.put("factInTop5", ratio(factHit, answerable));
            summary.put("answerableAboveThreshold", ratio(above, answerable));
            summary.put("unanswerableBelowThreshold", ratio(below, unanswerable));
            summaries.put(format.name(), summary);
            rows.put(format.name(), perQuestion);
            System.out.println("FORMAT EVAL " + format + " " + summary);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("measuredAt", Instant.now().toString());
        result.put("embeddingModel", "bge-m3:567m");
        result.put("threshold", qa.minSimilarity());
        result.put("summary", summaries);
        result.put("questions", rows);
        Files.writeString(ROOT.resolve("eval/results/retrieval-formats.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", StandardCharsets.UTF_8);

        // The new formats may not be worse than the PDFs of the same text (ADR-0016).
        @SuppressWarnings("unchecked")
        double pdfFacts = (double) ((Map<String, Object>) summaries.get("PDF")).get("factInTop5");
        for (String format : List.of("DOCX", "MD")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) summaries.get(format);
            assertThat((double) summary.get("documentHitAt5")).as(format + " document@5").isGreaterThanOrEqualTo(0.9);
            assertThat((double) summary.get("factInTop5")).as(format + " fact@5").isGreaterThanOrEqualTo(pdfFacts - 0.05);
        }
    }

    private void ingest(String account, DocumentFormat format) throws Exception {
        List<UUID> ids = new ArrayList<>();
        try (Stream<Path> sources = Files.list(ROOT.resolve("samples/src"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".txt")).sorted().toList()) {
                String stem = stem(source.getFileName().toString());
                byte[] bytes = switch (format) {
                    case PDF -> Files.readAllBytes(ROOT.resolve("samples/" + stem + ".pdf"));
                    case DOCX -> docx(lines(source));
                    case MD -> markdown(lines(source)).getBytes(StandardCharsets.UTF_8);
                    case TXT -> String.join("\n", lines(source)).getBytes(StandardCharsets.UTF_8);
                };
                String name = stem + "." + format.name().toLowerCase(Locale.ROOT);
                DocumentRow row = documents.insert(account, name, format, bytes.length, null, Instant.now());
                documents.insertFile(row.id(), bytes);
                ids.add(row.id());
            }
        }
        for (int round = 0; round < 60; round++) {
            while (worker.isPaused()) Thread.sleep(200);
            worker.runOnce();
            if (ids.stream().allMatch(id -> documents.find(account, id).orElseThrow().status().name().equals("READY"))) return;
        }
        throw new IllegalStateException(format + " samples did not become READY; is bge-m3:567m served on the eval Ollama?");
    }

    private static List<String> lines(Path source) throws Exception {
        return Files.readAllLines(source, StandardCharsets.UTF_8).stream().filter(l -> !l.equals("---")).toList();
    }

    private static byte[] docx(List<String> lines) {
        StringBuilder body = new StringBuilder();
        for (String line : lines) {
            if (line.isBlank()) continue;
            body.append(HEADING.matcher(line).matches() ? TestDocx.heading("Balk1", line) : TestDocx.p(line));
        }
        return TestDocx.document(body.toString());
    }

    private static String markdown(List<String> lines) {
        StringBuilder md = new StringBuilder();
        for (String line : lines) md.append(HEADING.matcher(line).matches() ? "## " + line : line).append("\n\n");
        return md.toString();
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    /** The expected fact as a whole word or number, as scripts/eval.mjs matches it ("30" is not in "30.000"). */
    static boolean containsFact(String text, String fact) {
        String haystack = lower(MARKER.matcher(text).replaceAll(" "));
        String needle = Pattern.quote(lower(fact));
        return Pattern.compile("(?<![\\p{L}\\p{N}]|\\p{N}[.,])" + needle + "(?![\\p{L}\\p{N}]|[.,]\\p{N})")
                .matcher(haystack).find();
    }

    private static String lower(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).toLowerCase(Locale.forLanguageTag("tr"));
    }

    private static double ratio(int part, int whole) {
        return round(whole == 0 ? 0 : (double) part / whole);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
