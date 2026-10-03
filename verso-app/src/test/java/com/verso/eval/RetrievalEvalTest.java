package com.verso.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.config.QaProperties;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * searched like a question to /v1/questions. Measures recall@1/@5 and MRR of the expected page, and how the best
 * similarity separates answerable from unanswerable questions (the 0.50 threshold, llm-rules 3.4). Writes
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
        VersoTestEnvironment.Models.class})
@Import(TestChatModel.Config.class)
class RetrievalEvalTest {

    private static final Path ROOT = VersoPostgres.repoRoot();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    QaProperties qa;

    @Autowired
    DocumentRetrieval retrieval;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    private final String account = "acct-eval-" + UUID.randomUUID();

    @Test
    void evalSet_whenSearched_findsTheExpectedPages() throws Exception {
        jdbc.update("DELETE FROM document.document");
        ingestSamples();
        JsonNode set = JSON.readTree(Files.readString(ROOT.resolve("eval/eval-set.json"), StandardCharsets.UTF_8));

        List<Map<String, Object>> rows = new ArrayList<>();
        int answerable = 0, hit1 = 0, hit5 = 0, docHit5 = 0, aboveThreshold = 0, unanswerable = 0, belowThreshold = 0;
        double reciprocal = 0;
        double threshold = qa.minSimilarity(); // the shipped threshold, not a copy (phase 8 review L1)
        for (JsonNode q : set.get("questions")) {
            long started = System.nanoTime();
            List<RetrievedPassage> found = retrieval.search(account, q.get("question").asString(), 5);
            long ms = (System.nanoTime() - started) / 1_000_000;
            double best = found.isEmpty() ? 0 : found.getFirst().similarity();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", q.get("id").asString());
            row.put("answerable", q.get("answerable").asBoolean());
            row.put("bestSimilarity", round(best));
            row.put("searchMs", ms);
            row.put("top5", found.stream().map(p -> p.fileName() + "#" + p.pageNumber() + " " + round(p.similarity())).toList());
            if (q.get("answerable").asBoolean()) {
                answerable++;
                String doc = q.get("document").asString();
                List<Integer> pages = new ArrayList<>();
                q.get("pages").forEach(p -> pages.add(p.asInt()));
                int rank = 0;
                for (int i = 0; i < found.size(); i++) {
                    if (found.get(i).fileName().equals(doc) && pages.contains(found.get(i).pageNumber())) {
                        rank = i + 1;
                        break;
                    }
                }
                boolean docFound = found.stream().anyMatch(p -> p.fileName().equals(doc));
                if (rank == 1) hit1++;
                if (rank >= 1) {
                    hit5++;
                    reciprocal += 1.0 / rank;
                }
                if (docFound) docHit5++;
                if (best >= threshold) aboveThreshold++;
                row.put("rank", rank);
            } else {
                unanswerable++;
                if (best < threshold) belowThreshold++;
            }
            rows.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("embeddingModel", "bge-m3:567m");
        summary.put("topK", 5);
        summary.put("threshold", threshold);
        summary.put("answerable", answerable);
        summary.put("unanswerable", unanswerable);
        summary.put("recallAt1", ratio(hit1, answerable));
        summary.put("recallAt5", ratio(hit5, answerable));
        summary.put("documentRecallAt5", ratio(docHit5, answerable));
        summary.put("mrr", round(reciprocal / answerable));
        summary.put("answerableAboveThreshold", ratio(aboveThreshold, answerable));
        summary.put("unanswerableBelowThreshold", ratio(belowThreshold, unanswerable));
        summary.put("answerableBestSimilarity", stats(rows, true));
        summary.put("unanswerableBestSimilarity", stats(rows, false));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("measuredAt", Instant.now().toString());
        result.put("summary", summary);
        result.put("questions", rows);
        Path out = ROOT.resolve("eval/results/retrieval.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8);
        System.out.println("RETRIEVAL EVAL " + summary);

        // Regression guard at the measured level (eval/README.md); a change that drops below it needs a decision.
        assertThat((double) summary.get("recallAt5")).as("recall@5").isGreaterThanOrEqualTo(0.8);
        // The threshold must never turn an answerable question into "bulunamadı" (the margin is thin: 0.522 vs 0.50).
        assertThat((double) summary.get("answerableAboveThreshold")).as("answerable above the threshold").isEqualTo(1.0);
    }

    private void ingestSamples() throws IOException, InterruptedException {
        List<UUID> ids = new ArrayList<>();
        try (Stream<Path> pdfs = Files.list(ROOT.resolve("samples"))) {
            for (Path pdf : pdfs.filter(p -> p.toString().endsWith(".pdf")).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(pdf);
                DocumentRow row = documents.insert(account, pdf.getFileName().toString(), DocumentFormat.PDF, bytes.length, null, Instant.now());
                documents.insertFile(row.id(), bytes);
                ids.add(row.id());
            }
        }
        for (int round = 0; round < 60; round++) {
            while (worker.isPaused()) Thread.sleep(200);
            worker.runOnce();
            if (ids.stream().allMatch(id -> documents.find(account, id).orElseThrow().status().name().equals("READY"))) return;
        }
        throw new IllegalStateException("samples did not become READY; is bge-m3:567m served on the eval Ollama?");
    }

    private static Map<String, Object> stats(List<Map<String, Object>> rows, boolean answerable) {
        List<Double> values = rows.stream().filter(r -> r.get("answerable").equals(answerable))
                .map(r -> (Double) r.get("bestSimilarity")).sorted().toList();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("min", values.getFirst());
        s.put("median", values.get(values.size() / 2));
        s.put("max", values.getLast());
        return s;
    }

    private static double ratio(int part, int whole) {
        return round(whole == 0 ? 0 : (double) part / whole);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
