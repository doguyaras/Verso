package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The evidence of ADR-0060 as a regression guard, without Docker or a model:
 *
 * <ul>
 *   <li>the labelled set {@code eval/kart-eslesme-set.json} (70 Turkish procurement/HR/IT pairs, paraphrase vs
 *       near-miss) with <b>assigned</b> cosine values: precision and recall per threshold, with and without the
 *       key-facts guard; the guard's verdict itself is deterministic and does not depend on the cosine;</li>
 *   <li>the measured set of ADR-0049 ({@code eval/embedding-olcumu/kart-esik-set.json}, real bge-m3 cosines in
 *       docs/evidence/onayli-bilgi-dogrulama.md): how many real paraphrases the guard refuses and how many real
 *       near-misses it catches.</li>
 * </ul>
 *
 * The match threshold may only come down where the guard keeps precision at 1.0 on the near-misses; the numbers
 * asserted here are the ones written in docs/evidence/kart-eslesme-olcumu.md.
 */
class CardMatchEvalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final double CONFIGURED_THRESHOLD = 0.95;

    private record Pair(String id, boolean paraphrase, String differs, double cosine, boolean guardPasses) {
    }

    private record Score(int paraphrasesMatched, int paraphrases, int nearMissesMatched) {

        double precision() {
            int matched = paraphrasesMatched + nearMissesMatched;
            return matched == 0 ? 1.0 : (double) paraphrasesMatched / matched;
        }

        double recall() {
            return (double) paraphrasesMatched / paraphrases;
        }
    }

    @Test
    void labelledSet_isLargeEnough_coversTheThreeDomains_andHasNoEInvoiceQuestion() throws IOException {
        JsonNode set = labelledSet();
        List<Pair> pairs = pairs(set);
        assertThat(pairs).hasSizeGreaterThanOrEqualTo(60);
        assertThat(pairs.stream().filter(Pair::paraphrase).count()).isGreaterThanOrEqualTo(30);
        assertThat(pairs.stream().filter(p -> !p.paraphrase()).count()).isGreaterThanOrEqualTo(30);
        List<String> domains = new ArrayList<>();
        for (JsonNode pair : set.path("pairs")) {
            domains.add(pair.path("domain").asString());
            String text = (pair.path("card").asString() + " " + pair.path("question").asString()).toLowerCase(Locale.ROOT);
            assertThat(text).as(pair.path("id").asString()).doesNotContain("e-fatura").doesNotContain("efatura")
                    .doesNotContain("e-arşiv");
        }
        assertThat(domains).contains("satinalma", "ik", "bt").allMatch(d -> List.of("satinalma", "ik", "bt").contains(d));
    }

    @Test
    void guard_rejectsEveryKeyFactNearMiss_whateverTheCosine() throws IOException {
        List<Pair> pairs = pairs(labelledSet());
        List<String> missed = pairs.stream().filter(p -> !p.paraphrase() && !"intent".equals(p.differs()))
                .filter(Pair::guardPasses).map(Pair::id).toList();
        assertThat(missed).as("key-fact near-misses the guard let through").isEmpty();
        // An intent near-miss ("ne kadar sürede" / "kime") has no differing key fact: the guard cannot see it.
        assertThat(pairs.stream().filter(p -> "intent".equals(p.differs())).allMatch(Pair::guardPasses)).isTrue();
        List<String> refused = pairs.stream().filter(Pair::paraphrase).filter(p -> !p.guardPasses()).map(Pair::id)
                .toList();
        // A yes/no question that states a value the card's question leaves open ("en çok iki ay mı?").
        assertThat(refused).as("paraphrases the guard refuses").containsExactly("h-p4");
    }

    @Test
    void precisionAndRecall_withAndWithoutTheGuard_decideTheThreshold() throws IOException {
        List<Pair> pairs = pairs(labelledSet());
        StringBuilder table = new StringBuilder("| eşik | guard yok: P / R (yakın ıska) | guard var: P / R (yakın ıska) |\n");
        double lowestSafe = Double.NaN;
        for (int step = 97; step >= 85; step--) {
            double threshold = step / 100.0;
            Score without = score(pairs, threshold, false);
            Score with = score(pairs, threshold, true);
            table.append(String.format(Locale.ROOT, "| %.2f | %.3f / %.3f (%d) | %.3f / %.3f (%d) |%n", threshold,
                    without.precision(), without.recall(), without.nearMissesMatched(), with.precision(),
                    with.recall(), with.nearMissesMatched()));
            if (with.nearMissesMatched() == 0) lowestSafe = threshold;
        }
        System.out.print(table);

        Score atConfigured = score(pairs, CONFIGURED_THRESHOLD, true);
        Score withoutAtConfigured = score(pairs, CONFIGURED_THRESHOLD, false);
        Score atNinety = score(pairs, 0.90, true);
        assertThat(withoutAtConfigured.nearMissesMatched()).as("cosine alone at 0.95").isEqualTo(28);
        assertThat(withoutAtConfigured.paraphrasesMatched()).isEqualTo(4);
        assertThat(atConfigured.nearMissesMatched()).as("guard at 0.95").isZero();
        assertThat(atConfigured.precision()).isEqualTo(1.0);
        assertThat(atConfigured.paraphrasesMatched()).isEqualTo(4);
        // At 0.90 the guard lets the intent near-misses at 0.944, 0.92 and 0.91 through: precision < 1.0, so the
        // threshold stays at 0.95 (ADR-0060). 0.95 is also the lowest threshold where it is 1.0.
        assertThat(atNinety.nearMissesMatched()).isEqualTo(3);
        assertThat(atNinety.paraphrasesMatched()).isEqualTo(24);
        assertThat(atNinety.precision()).isLessThan(1.0);
        assertThat(lowestSafe).isEqualTo(CONFIGURED_THRESHOLD);
    }

    /**
     * The real set of ADR-0049 (64 paraphrases, 30 near-misses; cosines measured with bge-m3): the guard alone, per
     * pair of a probe and its own card.
     */
    @Test
    void measuredSet_guardRefusesFewRealParaphrases_andCatchesTheNearMissesThatDifferInAKeyFact() throws IOException {
        JsonNode set = JSON.readTree(Files.readString(repoRoot().resolve("eval/embedding-olcumu/kart-esik-set.json")));
        List<String> refused = new ArrayList<>();
        List<String> caught = new ArrayList<>();
        int paraphrases = 0;
        int nearMisses = 0;
        for (JsonNode card : set.path("cards")) {
            KeyFacts cardFacts = KeyFacts.of(card.path("question").asString());
            String id = card.path("id").asString();
            for (JsonNode p : card.path("paraphrases")) {
                paraphrases++;
                if (KeyFacts.of(p.asString()).conflictWith(cardFacts).isPresent()) refused.add(id);
            }
            for (JsonNode n : card.path("nearMiss")) {
                nearMisses++;
                if (KeyFacts.of(n.asString()).conflictWith(cardFacts).isPresent()) caught.add(id);
            }
        }
        assertThat(paraphrases).isEqualTo(64);
        assertThat(nearMisses).isEqualTo(30);
        // "Talebim 48 saattir onay bekliyor" for "iki gündür": the same duration in another unit.
        assertThat(refused).containsExactly("erp-3");
        // izin-1 (0.941) and bt-1 (0.933), two of the three near-misses above 0.93, are caught; guvenlik-3 (0.944,
        // "ne kadar sürede" / "kime") is not: it differs in intent, not in a key fact.
        assertThat(caught).containsExactly("izin-1", "erp-1", "bt-1", "satin-2", "satin-3");
    }

    private static Score score(List<Pair> pairs, double threshold, boolean guard) {
        int paraphrases = 0;
        int matchedParaphrases = 0;
        int matchedNearMisses = 0;
        for (Pair pair : pairs) {
            boolean match = pair.cosine() >= threshold && (!guard || pair.guardPasses());
            if (pair.paraphrase()) {
                paraphrases++;
                if (match) matchedParaphrases++;
            } else if (match) {
                matchedNearMisses++;
            }
        }
        return new Score(matchedParaphrases, paraphrases, matchedNearMisses);
    }

    private static List<Pair> pairs(JsonNode set) {
        List<Pair> pairs = new ArrayList<>();
        for (JsonNode pair : set.path("pairs")) {
            boolean guardPasses = KeyFacts.of(pair.path("question").asString())
                    .conflictWith(KeyFacts.of(pair.path("card").asString())).isEmpty();
            pairs.add(new Pair(pair.path("id").asString(), "paraphrase".equals(pair.path("label").asString()),
                    pair.path("differs").asString(null), pair.path("cosine").asDouble(), guardPasses));
        }
        return pairs;
    }

    private static JsonNode labelledSet() throws IOException {
        try (InputStream in = CardMatchEvalTest.class.getResourceAsStream("/eval/kart-eslesme-set.json")) {
            assertThat(in).as("eval/kart-eslesme-set.json on the test classpath").isNotNull();
            return JSON.readTree(in);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("eval/embedding-olcumu/kart-esik-set.json"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("repository root with eval/embedding-olcumu/kart-esik-set.json").isNotNull();
        return dir;
    }
}
