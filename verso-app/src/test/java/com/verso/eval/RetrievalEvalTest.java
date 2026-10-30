package com.verso.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.config.DoclingProperties;
import com.verso.document.worker.DocumentTextExtractor;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.config.QaProperties;
import com.verso.qa.service.AnswerabilityGate;
import com.verso.qa.service.AnswerabilityGate.Decision;
import com.verso.support.TestChatModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoPostgres;
import com.verso.support.VersoTestEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
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
 * Retrieval quality on the Turkish eval set (phase 8, llm-rules 7.1) with the real embedding model: the sample PDFs
 * are ingested by the real worker into a real PostgreSQL + pgvector, then every question of eval/eval-set.json is
 * searched like a question to /v1/questions (hybrid search, ADR-0022). Measures recall@1/@5 and MRR of the expected
 * page, overall and per question type, and how the shipped answerability gate (AnswerabilityGate, llm-rules 3.4)
 * treats answerable and unanswerable questions: precision and recall of its "bulunamadı". Writes
 * eval/results/retrieval.json. Not part of the build (tag "eval"); needs Ollama with bge-m3:567m on
 * VERSO_EVAL_OLLAMA_URL (default http://localhost:11434, e.g. deploy/compose.local.yaml):
 *
 * <pre>./mvnw -pl verso-app -am test -Dverso.excludedGroups=none -Dgroups=eval -Dtest=RetrievalEvalTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false</pre>
 */
@Tag("eval")
@SpringBootTest(properties = {
        "spring.ai.ollama.base-url=${VERSO_EVAL_OLLAMA_URL:http://localhost:11434}",
        "spring.ai.ollama.embedding.model=bge-m3:567m",
        "spring.http.clients.read-timeout=120s"})
// Not @VersoTestEnvironment: that one replaces the embedding model; here the real one embeds.
@ContextConfiguration(initializers = {VersoPostgres.Initializer.class, TestIdp.Initializer.class,
        VersoTestEnvironment.Models.class, RetrievalEvalTest.ProductionEmbeddingTimeout.class})
@Import(TestChatModel.Config.class)
class RetrievalEvalTest {

    /**
     * The production question-embedding timeout (10 s, ADR-0008) instead of the 2 s of the other tests: on a shared,
     * busy host a CPU embedding sometimes took longer than 2 s and failed the whole measurement (ADR-0032 runs).
     */
    static final class ProductionEmbeddingTimeout
            implements org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
        @Override
        public void initialize(org.springframework.context.ConfigurableApplicationContext context) {
            org.springframework.boot.test.util.TestPropertyValues.of("verso.document.retrieval.embedding-timeout=10s")
                    .applyTo(context);
        }
    }

    private static final Path ROOT = VersoPostgres.repoRoot();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> TAGS = List.of("paraphrase", "abbreviation", "number", "table", "disambiguation",
            "on-topic");

    @Autowired
    QaProperties qa;

    @Autowired
    AnswerabilityGate gate;

    @Autowired
    DocumentRetrieval retrieval;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    private final String account = "acct-eval-" + UUID.randomUUID();

    /** One question's outcome. rank: 1-based position of the expected page in the top 5, 0 when absent. */
    private record Outcome(JsonNode question, int rank, boolean documentFound, boolean answered) {
        boolean answerable() {
            return question.get("answerable").asBoolean();
        }

        boolean tagged(String tag) {
            JsonNode tags = question.get("tags");
            if (tags == null) return false;
            for (JsonNode t : tags) if (t.asString().equals(tag)) return true;
            return false;
        }
    }

    @Autowired
    DocumentTextExtractor extractor;

    @Autowired
    DoclingProperties docling;

    /**
     * ADR-0032 measurement switches, all optional: -Dverso.eval.samples-dir (another set of the same eleven PDFs, e.g.
     * the ruled-table or scanned variants SamplePdfsTest writes), -Dverso.eval.out (result file name in eval/results)
     * and the sidecar itself (-Dverso.document.docling.enabled=true -Dverso.document.docling.base-url=...).
     */
    private static final String SAMPLES_DIR = System.getProperty("verso.eval.samples-dir");
    private static final String OUT = System.getProperty("verso.eval.out", "retrieval.json");

    @Test
    void evalSet_whenSearched_findsTheExpectedPages() throws Exception {
        jdbc.update("DELETE FROM document.document");
        Map<String, Object> parsing = parseSamples();
        Map<String, Object> ingestion = ingestSamples();
        JsonNode set = JSON.readTree(Files.readString(ROOT.resolve("eval/eval-set.json"), StandardCharsets.UTF_8));

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Outcome> outcomes = new ArrayList<>();
        List<Long> searchMs = new ArrayList<>();
        for (JsonNode q : set.get("questions")) {
            long started = System.nanoTime();
            List<RetrievedPassage> found = retrieval.search(account, q.get("question").asString(), 5);
            long ms = (System.nanoTime() - started) / 1_000_000;
            searchMs.add(ms);
            // Fused order (ADR-0022): the most similar passage is not necessarily the first one.
            RetrievedPassage best = found.stream().max(Comparator.comparingDouble(RetrievedPassage::similarity)).orElse(null);
            Decision decision = gate.decide(found);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", q.get("id").asString());
            row.put("answerable", q.get("answerable").asBoolean());
            // Four decimals: the gate's edges sit within 0.001 of measured questions (eval/README.md).
            row.put("bestSimilarity", best == null ? 0 : Math.round(best.similarity() * 10_000) / 10_000.0);
            row.put("bestSpecificTermShare", best == null ? 1 : Math.round(best.specificTermShare() * 10_000) / 10_000.0);
            row.put("decision", decision.name());
            row.put("searchMs", ms);
            row.put("top5", found.stream().map(p -> p.fileName() + "#" + p.pageNumber() + " " + round(p.similarity())).toList());
            int rank = 0;
            boolean docFound = false;
            if (q.get("answerable").asBoolean()) {
                String doc = q.get("document").asString();
                List<Integer> pages = new ArrayList<>();
                q.get("pages").forEach(p -> pages.add(p.asInt()));
                for (int i = 0; i < found.size(); i++) {
                    if (found.get(i).fileName().equals(doc) && pages.contains(found.get(i).pageNumber())) {
                        rank = i + 1;
                        break;
                    }
                }
                docFound = found.stream().anyMatch(p -> p.fileName().equals(doc));
                row.put("rank", rank);
            }
            outcomes.add(new Outcome(q, rank, docFound, decision != Decision.NONE));
            rows.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("embeddingModel", "bge-m3:567m");
        summary.put("topK", 5);
        summary.put("samples", SAMPLES_DIR == null ? "samples" : Path.of(SAMPLES_DIR).getFileName().toString());
        summary.put("docling", docling.enabled());
        summary.put("parsing", parsing);
        summary.put("ingestion", ingestion);
        summary.put("gate", Map.of("minSimilarity", qa.minSimilarity(), "lexicalMinSimilarity", qa.lexicalMinSimilarity(),
                "specificTermMaxShare", qa.specificTermMaxShare()));
        summary.putAll(measure(outcomes, o -> true));
        Map<String, Object> byTag = new LinkedHashMap<>();
        byTag.put("version1", measure(outcomes, o -> o.question().get("tags") == null));
        byTag.put("version2", measure(outcomes, o -> o.question().get("tags") != null));
        for (String tag : TAGS) byTag.put(tag, measure(outcomes, o -> o.tagged(tag)));
        summary.put("byTag", byTag);
        summary.put("answerableBestSimilarity", stats(rows, true));
        summary.put("unanswerableBestSimilarity", stats(rows, false));
        List<Long> sorted = searchMs.stream().sorted().toList();
        summary.put("searchMs", Map.of("p50", sorted.get(sorted.size() / 2),
                "p95", sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1), "max", sorted.getLast()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("measuredAt", Instant.now().toString());
        result.put("summary", summary);
        result.put("questions", rows);
        Path out = ROOT.resolve("eval/results").resolve(Path.of(OUT).getFileName());
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8);
        System.out.println("RETRIEVAL EVAL " + summary);
        // The guards hold the committed samples; a variant set (ruled tables, scanned pages) is a measurement only.
        if (SAMPLES_DIR != null) return;

        // Regression guards at the measured level of ADR-0022 (eval/README.md "Eşikler ve karar"); a change that drops
        // below them needs a decision, not a quieter guard.
        assertThat((double) summary.get("recallAt5")).as("recall@5").isGreaterThanOrEqualTo(0.97);
        assertThat((double) summary.get("mrr")).as("MRR").isGreaterThanOrEqualTo(0.86);
        // ADR-0022 let every answerable question through. With the context header in the embedding (ADR-0061) three
        // fall below the gate (bt-16 0.4358, isg-10 0.4611, depo-17 0.4625; 109/112): a measured, accepted loss
        // (ADR-0063), not a target. One more lost question (108/112 = 0.964) turns this red.
        assertThat((double) summary.get("answerablePassGate")).as("answerable questions through the gate")
                .isGreaterThanOrEqualTo(0.97);
        assertThat((double) summary.get("unanswerableStoppedByGate")).as("unanswerable questions stopped by the gate")
                .isGreaterThanOrEqualTo(0.2);
    }

    /** Recall, MRR and the gate's confusion counts over the questions the filter keeps. */
    private static Map<String, Object> measure(List<Outcome> all, Predicate<Outcome> filter) {
        List<Outcome> outcomes = all.stream().filter(filter).toList();
        List<Outcome> answerable = outcomes.stream().filter(Outcome::answerable).toList();
        List<Outcome> unanswerable = outcomes.stream().filter(o -> !o.answerable()).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("answerable", answerable.size());
        m.put("unanswerable", unanswerable.size());
        if (!answerable.isEmpty()) {
            m.put("recallAt1", ratio(answerable.stream().filter(o -> o.rank() == 1).count(), answerable.size()));
            m.put("recallAt5", ratio(answerable.stream().filter(o -> o.rank() >= 1).count(), answerable.size()));
            m.put("hitsAt5", answerable.stream().filter(o -> o.rank() >= 1).count());
            m.put("documentRecallAt5", ratio(answerable.stream().filter(Outcome::documentFound).count(), answerable.size()));
            m.put("mrr", round(answerable.stream().mapToDouble(o -> o.rank() == 0 ? 0 : 1.0 / o.rank()).sum() / answerable.size()));
            m.put("answerablePassGate", ratio(answerable.stream().filter(Outcome::answered).count(), answerable.size()));
        }
        long answerableStopped = answerable.stream().filter(o -> !o.answered()).count();
        long unanswerableStopped = unanswerable.stream().filter(o -> !o.answered()).count();
        if (!unanswerable.isEmpty()) m.put("unanswerableStoppedByGate", ratio(unanswerableStopped, unanswerable.size()));
        // "bulunamadı" from the gate as the positive class: of the questions it stopped, how many had no answer
        // (precision), and of the questions without an answer, how many it stopped (recall).
        m.put("notFoundPrecision", ratio(unanswerableStopped, answerableStopped + unanswerableStopped));
        m.put("notFoundRecall", ratio(unanswerableStopped, unanswerable.size()));
        return m;
    }

    private static List<Path> samplePdfs() throws IOException {
        Path dir = SAMPLES_DIR == null ? ROOT.resolve("samples") : Path.of(SAMPLES_DIR);
        try (Stream<Path> pdfs = Files.list(dir)) {
            return pdfs.filter(p -> p.toString().endsWith(".pdf")).sorted().toList();
        }
    }

    /**
     * Parse time per page of the configured parser (ADR-0032), measured apart from the embeddings: every sample once
     * through the same extractor the worker uses. A file no parser can read counts as rejected.
     */
    private Map<String, Object> parseSamples() throws IOException {
        long totalMs = 0;
        int pages = 0;
        Map<String, Integer> parsers = new LinkedHashMap<>();
        Map<String, Object> perDocument = new LinkedHashMap<>();
        for (Path pdf : samplePdfs()) {
            byte[] bytes = Files.readAllBytes(pdf);
            long started = System.nanoTime();
            String outcome;
            int count = 0;
            try {
                DocumentTextExtractor.Extraction extraction = extractor.extract(DocumentFormat.PDF, bytes, () -> false);
                count = extraction.texts().size();
                outcome = extraction.parser() + (extraction.fallback() == null ? "" : " fallback " + extraction.fallback());
            } catch (RuntimeException e) {
                outcome = "rejected " + e.getMessage();
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            totalMs += ms;
            pages += count;
            parsers.merge(outcome, 1, Integer::sum);
            perDocument.put(pdf.getFileName().toString(), Map.of("pages", count, "ms", ms, "parser", outcome));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pages", pages);
        m.put("totalMs", totalMs);
        m.put("msPerPage", pages == 0 ? 0 : Math.round((double) totalMs / pages));
        m.put("parsers", parsers);
        m.put("documents", perDocument);
        return m;
    }

    /** Wall time of the worker until every sample is READY or FAILED (parse + chunk + embed + store). */
    private Map<String, Object> ingestSamples() throws IOException, InterruptedException {
        List<UUID> ids = new ArrayList<>();
        for (Path pdf : samplePdfs()) {
            byte[] bytes = Files.readAllBytes(pdf);
            DocumentRow row = documents.insert(account, pdf.getFileName().toString(), DocumentFormat.PDF, bytes.length, null, Instant.now());
            documents.insertFile(row.id(), bytes);
            ids.add(row.id());
        }
        long started = System.nanoTime();
        for (int round = 0; round < 60; round++) {
            while (worker.isPaused()) Thread.sleep(200);
            worker.runOnce();
            List<String> states = ids.stream().map(id -> documents.find(account, id).orElseThrow().status().name()).toList();
            if (states.stream().allMatch(s -> s.equals("READY") || s.equals("FAILED"))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("wallMs", (System.nanoTime() - started) / 1_000_000);
                m.put("ready", states.stream().filter("READY"::equals).count());
                m.put("failed", states.stream().filter("FAILED"::equals).count());
                m.put("chunks", jdbc.queryForObject("SELECT count(*) FROM document.document_chunk", Long.class));
                return m;
            }
        }
        throw new IllegalStateException("samples did not become READY; is bge-m3:567m served on the eval Ollama?");
    }

    private static Map<String, Object> stats(List<Map<String, Object>> rows, boolean answerable) {
        List<Double> values = rows.stream().filter(r -> r.get("answerable").equals(answerable))
                .map(r -> ((Number) r.get("bestSimilarity")).doubleValue()).sorted().toList();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("min", values.getFirst());
        s.put("median", values.get(values.size() / 2));
        s.put("max", values.getLast());
        return s;
    }

    private static double ratio(long part, long whole) {
        return round(whole == 0 ? 0 : (double) part / whole);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
