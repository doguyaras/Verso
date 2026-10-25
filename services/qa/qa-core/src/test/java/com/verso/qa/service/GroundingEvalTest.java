package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The measurement of ADR-0047 as a regression guard: the anchor check on eval/grounding-set.json (192 labelled
 * sentences against their cited pages of samples/src). Precision must stay 1.0 (a false "dayanağı doğrulanamadı" costs
 * trust), recall at least the measured 26 of 57. Fast and container-free: it runs with every build.
 */
class GroundingEvalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void anchorCheckFlagsOnlyUnsupportedSentencesOnTheLabelledSet() throws IOException {
        Path root = repoRoot();
        JsonNode set = JSON.readTree(Files.readString(root.resolve("eval/grounding-set.json"), StandardCharsets.UTF_8));
        Map<String, String> questions = new HashMap<>();
        for (JsonNode q : JSON.readTree(Files.readString(root.resolve("eval/eval-set.json"), StandardCharsets.UTF_8))
                .get("questions")) {
            questions.put(q.get("id").asString(), q.get("question").asString());
        }
        Map<String, List<String>> pages = new HashMap<>();
        int tp = 0;
        int fp = 0;
        int fn = 0;
        Map<String, int[]> byKind = new TreeMap<>();
        List<String> wrong = new ArrayList<>();
        for (JsonNode item : set.get("items")) {
            List<String> passages = new ArrayList<>();
            for (JsonNode cite : item.get("cites")) {
                String[] ref = cite.asString().split("#");
                List<String> document = pages.computeIfAbsent(ref[0], name -> read(root, name));
                passages.add(document.get(Integer.parseInt(ref[1]) - 1));
            }
            boolean flagged = AnchorCheck.check(item.get("sentence").asString(), passages,
                    questions.get(item.get("question").asString())).unverified();
            boolean unsupported = item.get("label").asString().equals("unsupported");
            if (flagged && unsupported) tp++;
            else if (flagged) {
                fp++;
                wrong.add(item.get("id").asString());
            } else if (unsupported) fn++;
            int[] kind = byKind.computeIfAbsent(item.get("kind").asString(), k -> new int[2]);
            if (flagged) kind[0]++;
            kind[1]++;
        }
        String counts = byKind.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()[0] + "/" + e.getValue()[1])
                .reduce((a, b) -> a + " " + b).orElse("");
        assertThat(wrong).as("supported sentences flagged; %s", counts).isEmpty();
        assertThat(fp).isZero();
        assertThat(tp).as("unsupported sentences found (measured 26 of 57); %s", counts).isGreaterThanOrEqualTo(26);
        assertThat(tp + fn).isEqualTo(57);
        // Only quantities and codes are in reach: negations and swapped names pass, by design.
        assertThat(byKind.get("negation")[0]).isZero();
        assertThat(byKind.get("rewrite")[0]).isZero();
        assertThat(byKind.get("number")[0]).isGreaterThanOrEqualTo(18);
    }

    private static List<String> read(Path root, String pdf) {
        try {
            String text = Files.readString(root.resolve("samples/src/" + pdf.replace(".pdf", ".txt")),
                    StandardCharsets.UTF_8).replace("\r", "");
            return List.of(text.split("\n---\n"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("eval/grounding-set.json"))) dir = dir.getParent();
        assertThat(dir).as("repository root with eval/grounding-set.json").isNotNull();
        return dir;
    }
}
