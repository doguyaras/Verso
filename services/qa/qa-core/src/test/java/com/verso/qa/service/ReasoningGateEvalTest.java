package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.service.ReasoningGate.Reason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The offline part of the ADR-0055 measurement, as a regression guard: how often the lexical gate fires on the
 * multi-hop set (it should) and on the single-hop eval set (each firing costs one decomposition call). The entity part
 * of the gate needs ingested documents and is measured with the real stack (docs/evidence/cok-adimli-akil-yurutme.md).
 */
class ReasoningGateEvalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void lexicalGate_onTheMultiHopAndSingleHopSets_firesOnMostMultiHopAndFewSingleQuestions() throws IOException {
        Path root = repoRoot();
        List<String> missed = new ArrayList<>();
        int multiHop = 0;
        int fired = 0;
        for (JsonNode q : JSON.readTree(Files.readString(root.resolve("eval/multihop-set.json"),
                StandardCharsets.UTF_8)).get("questions")) {
            multiHop++;
            if (ReasoningGate.lexical(q.get("question").asString()) != Reason.NONE) fired++;
            else missed.add(q.get("id").asString());
        }
        List<String> falsePositives = new ArrayList<>();
        int single = 0;
        for (JsonNode q : JSON.readTree(Files.readString(root.resolve("eval/eval-set.json"), StandardCharsets.UTF_8))
                .get("questions")) {
            single++;
            if (ReasoningGate.lexical(q.get("question").asString()) != Reason.NONE) {
                falsePositives.add(q.get("id").asString());
            }
        }
        // Measured for ADR-0055: 19/20 and 3/140 (docs/evidence/cok-adimli-akil-yurutme.md); the bounds keep a regression
        // visible.
        assertThat(multiHop).isEqualTo(20);
        assertThat((double) fired / multiHop).as("multi-hop recall; missed %s", missed).isGreaterThanOrEqualTo(0.90);
        assertThat((double) falsePositives.size() / single).as("single-hop firing rate; fired on %s", falsePositives)
                .isLessThanOrEqualTo(0.05);
        System.out.printf("reasoning gate: multihop %d/%d missed=%s; single-hop %d/%d fired=%s%n", fired, multiHop,
                missed, falsePositives.size(), single, falsePositives);
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("eval/multihop-set.json"))) dir = dir.getParent();
        assertThat(dir).as("repository root with eval/multihop-set.json").isNotNull();
        return dir;
    }
}
