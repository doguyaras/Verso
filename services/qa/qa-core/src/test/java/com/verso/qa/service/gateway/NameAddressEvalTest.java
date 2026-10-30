package com.verso.qa.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * ADR-0065: precision and recall of name and address detection on the labelled Turkish set
 * ({@code src/test/resources/pii/ad-adres-etiketli.txt}: corporate HR, procurement and IT sentences with hard
 * negatives). A finding counts only when its type and its span match the label exactly.
 *
 * <p>Thresholds (justified in ADR-0065): precision is the guarantee the answer depends on (a masked institution or
 * law breaks the answer; nothing restores the model's understanding), so it is held at 0.97 for each type. Recall is
 * the privacy side; the rules miss given names that are ordinary words and unlisted surnames by design, so it is held
 * at 0.90, a floor the measured value clears with a margin and that a regression in the lists or the grammar breaks.
 */
class NameAddressEvalTest {

    static final double MIN_PRECISION = 0.97;
    static final double MIN_RECALL = 0.90;

    private static final Pattern LABEL = Pattern.compile("\\{(AD|ADRES):([^}]*)}");

    record Span(int start, int end, TurkishPii.Type type) {
    }

    record Sentence(String text, Set<Span> gold) {
    }

    static List<Sentence> load() throws IOException {
        List<Sentence> sentences = new ArrayList<>();
        try (InputStream in = NameAddressEvalTest.class.getClassLoader()
                .getResourceAsStream("pii/ad-adres-etiketli.txt")) {
            assertThat(in).as("labelled set on the test classpath").isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String raw = line.strip();
                if (raw.isEmpty() || raw.startsWith("#")) continue;
                StringBuilder text = new StringBuilder();
                Set<Span> gold = new HashSet<>();
                Matcher m = LABEL.matcher(raw);
                int at = 0;
                while (m.find()) {
                    text.append(raw, at, m.start());
                    int start = text.length();
                    text.append(m.group(2));
                    gold.add(new Span(start, text.length(), TurkishPii.Type.valueOf(m.group(1))));
                    at = m.end();
                }
                text.append(raw.substring(at));
                sentences.add(new Sentence(text.toString(), gold));
            }
        }
        return sentences;
    }

    @Test
    void labelledSet_isLargeEnough_andHasHardNegatives() throws IOException {
        List<Sentence> set = load();
        assertThat(set).hasSizeGreaterThanOrEqualTo(150);
        assertThat(set.stream().filter(s -> s.gold().isEmpty()).count()).as("sentences without any label")
                .isGreaterThanOrEqualTo(50);
        assertThat(set.stream().flatMap(s -> s.gold().stream()).filter(g -> g.type() == TurkishPii.Type.ADRES)
                .count()).isGreaterThanOrEqualTo(25);
    }

    @Test
    void precisionAndRecall_perType_meetTheThresholds() throws IOException {
        List<Sentence> set = load();
        StringBuilder report = new StringBuilder();
        for (TurkishPii.Type type : List.of(TurkishPii.Type.AD, TurkishPii.Type.ADRES)) {
            int tp = 0;
            int fp = 0;
            int fn = 0;
            List<String> errors = new ArrayList<>();
            for (Sentence s : set) {
                Set<Span> found = new HashSet<>();
                for (TurkishPii.Match m : TurkishPii.find(s.text())) {
                    if (m.type() == type) found.add(new Span(m.start(), m.end(), type));
                }
                for (Span f : found) {
                    if (s.gold().contains(f)) {
                        tp++;
                    } else {
                        fp++;
                        errors.add("FP " + s.text().substring(f.start(), f.end()) + "  <=  " + s.text());
                    }
                }
                for (Span g : s.gold()) {
                    if (g.type() == type && !found.contains(g)) {
                        fn++;
                        errors.add("FN " + s.text().substring(g.start(), g.end()) + "  <=  " + s.text());
                    }
                }
            }
            double precision = tp + fp == 0 ? 1.0 : (double) tp / (tp + fp);
            double recall = tp + fn == 0 ? 1.0 : (double) tp / (tp + fn);
            report.append(String.format("%s: tp=%d fp=%d fn=%d precision=%.3f recall=%.3f%n", type, tp, fp, fn,
                    precision, recall));
            errors.forEach(e -> report.append("  ").append(e).append('\n'));
            System.out.print(report);
            assertThat(precision).as("%s precision%n%s", type, report).isGreaterThanOrEqualTo(MIN_PRECISION);
            assertThat(recall).as("%s recall%n%s", type, report).isGreaterThanOrEqualTo(MIN_RECALL);
            report.setLength(0);
        }
    }
}
