package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.config.QaRelevanceProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ADR-0064 as a regression guard on recorded answers: the relevance check replayed on the model's answers of the
 * internal eval set (eval/results/e2e-internal-p55.json, ADR-0063) and of the multi-hop set (ADR-0055). No answer
 * whose expected fact was right may become "not found"; the unanswerable questions refused must rise to the measured
 * count. Fast and container-free: it runs with every build. Cited passages are not recorded, so the passage side of
 * the quantity check is not replayed here (the unit tests cover it).
 */
class RelevanceEvalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AnswerRelevance CHECK = new AnswerRelevance(new QaRelevanceProperties(true));

    @Test
    void recordedAnswersOfTheInternalSetLoseNoRightFactAndRefuseMore() throws IOException {
        Path root = repoRoot();
        Map<String, String> questions = questions(root.resolve("eval/eval-set.json"));
        JsonNode run = JSON.readTree(Files.readString(root.resolve("eval/results/e2e-internal-p55.json"),
                StandardCharsets.UTF_8));
        List<String> lost = new ArrayList<>();
        int unanswerable = 0;
        int refusedBefore = 0;
        int refusedAfter = 0;
        for (JsonNode row : run.get("questions")) {
            if (row.get("status").asInt() != 200) continue;
            boolean before = !row.get("answerable").asBoolean()
                    && (row.get("saysNotFound").asBoolean() || row.get("ownRefusal").asBoolean());
            boolean abstains = abstains(questions.get(row.get("id").asString()), row, false);
            if (row.get("answerable").asBoolean()) {
                if (abstains && row.get("factFound").asBoolean()) lost.add(row.get("id").asString());
                continue;
            }
            unanswerable++;
            if (before) refusedBefore++;
            if (before || abstains) refusedAfter++;
        }
        assertThat(lost).as("answerable questions with the right fact turned into not found").isEmpty();
        assertThat(unanswerable).isEqualTo(28);
        assertThat(refusedBefore).as("p55 run: notFoundOrOwnRefusal 0.786").isEqualTo(22);
        assertThat(refusedAfter).as("measured on the recorded answers").isGreaterThanOrEqualTo(26);
    }

    @Test
    void recordedMultiHopAnswersKeepEveryFactAndTheirPartialAnswers() throws IOException {
        Path root = repoRoot();
        Map<String, String> questions = questions(root.resolve("eval/multihop-set.json"));
        List<String> lost = new ArrayList<>();
        for (String arm : List.of("off", "on")) {
            JsonNode run = JSON.readTree(Files.readString(
                    root.resolve("eval/results/multihop-gemma4_e2b-" + arm + ".json"), StandardCharsets.UTF_8));
            for (JsonNode row : run.get("questions")) {
                if (row.get("status").asInt() != 200) continue;
                boolean multiStep = row.path("steps").asInt() > 0;
                if (abstains(questions.get(row.get("id").asString()), row, multiStep)
                        && row.get("factHits").asInt() > 0) {
                    lost.add(arm + ":" + row.get("id").asString());
                }
            }
        }
        assertThat(lost).as("multi-hop answers with a right fact turned into not found").isEmpty();
    }

    /** What the service would do with this recorded answer: true when it becomes "not found" now and was not before. */
    private static boolean abstains(String question, JsonNode row, boolean multiStep) {
        String answer = row.get("answer").asString();
        boolean sourced = row.get("found").asBoolean();
        return !CHECK.check(question, answer, List.of(), sourced, multiStep).answered();
    }

    private static Map<String, String> questions(Path set) throws IOException {
        Map<String, String> byId = new HashMap<>();
        for (JsonNode q : JSON.readTree(Files.readString(set, StandardCharsets.UTF_8)).get("questions")) {
            byId.put(q.get("id").asString(), q.get("question").asString());
        }
        return byId;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("eval/eval-set.json"))) dir = dir.getParent();
        assertThat(dir).as("repository root with eval/eval-set.json").isNotNull();
        return dir;
    }
}
