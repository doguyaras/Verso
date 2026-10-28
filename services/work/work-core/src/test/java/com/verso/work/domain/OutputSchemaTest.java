package com.verso.work.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillOutput;
import com.verso.work.api.enums.SkillCitationKind;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The output contract of ADR-0057: parsing the model's JSON, the evidence-or-"değerlendirilemez" rule, limits, closed
 * values, the total cap, forbidden phrases, and the ADR-0047 grounding of numbers and codes.
 */
class OutputSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Function<String, Object> READ = text -> JSON.readValue(text, Object.class);
    private final SkillDefinition skill = TestSkills.valid();

    @Test
    void parse_whenJsonIsFencedWithChatter_readsItInSchemaOrder() {
        String text = """
                İşte çıktı:
                ```json
                {"sections":{"summary":{"text":"Bütçe onaylandı.","evidence":[2]},
                 "decisions":{"rows":[{"decision":"Bütçe onaylandı","level":"yüksek","evidence":[1,1,2]}]}}}
                ```""";
        OutputSchema.Checked c = OutputSchema.parse(skill, text, 2, READ);
        assertThat(c.errors()).isEmpty();
        assertThat(c.output().sections()).extracting(SkillOutput.Section::key).containsExactly("decisions", "summary");
        SkillOutput.Row row = c.output().sections().getFirst().rows().getFirst();
        assertThat(row.cells()).containsEntry("level", "Yüksek");
        assertThat(row.evidence()).containsExactly(1, 2);
    }

    @Test
    void parse_whenRowSaysDegerlendirilemezInAnySpelling_acceptsItWithoutEvidence() {
        for (String word : List.of("değerlendirilemez", "DEĞERLENDİRİLEMEZ", "degerlendirilemez")) {
            String text = "{\"sections\":{\"decisions\":{\"rows\":[{\"decision\":\"Tarih\",\"level\":\"\",\"evidence\":\""
                    + word + "\"}]},\"summary\":{\"text\":\"Yok.\",\"evidence\":\"" + word + "\"}}}";
            OutputSchema.Checked c = OutputSchema.parse(skill, text, 0, READ);
            assertThat(c.errors()).as(word).isEmpty();
            assertThat(c.output().sections().getFirst().rows().getFirst().notAssessable()).isTrue();
            assertThat(c.output().sections().get(1).notAssessable()).isTrue();
        }
    }

    @Test
    void parse_whenEvidenceIsMissingOrPointsNowhere_reportsRulesNotText() {
        String text = "{\"sections\":{\"decisions\":{\"rows\":[{\"decision\":\"MarkerSecret\",\"level\":\"Orta\"},"
                + "{\"decision\":\"B\",\"level\":\"Orta\",\"evidence\":[7]}]},\"summary\":{\"text\":\"x\",\"evidence\":[1]}}}";
        OutputSchema.Checked c = OutputSchema.parse(skill, text, 2, READ);
        assertThat(c.valid()).isFalse();
        assertThat(c.errors()).contains("sections.decisions.rows[0].evidence: cite sources or say \"değerlendirilemez\"");
        String again = text.replace("{\"decision\":\"MarkerSecret\",\"level\":\"Orta\"}",
                "{\"decision\":\"A\",\"level\":\"Orta\",\"evidence\":[1]}");
        assertThat(OutputSchema.parse(skill, again, 2, READ).errors())
                .containsExactly("sections.decisions.rows[1].evidence: source 7 does not exist");
        assertThat(String.join(" ", c.errors())).doesNotContain("MarkerSecret");
    }

    @Test
    void parse_whenNotJsonOrSectionMissing_reportsIt() {
        assertThat(OutputSchema.parse(skill, "Üzgünüm, yapamam.", 1, READ).errors()).containsExactly("output: no JSON object");
        assertThat(OutputSchema.parse(skill, "{oops}", 1, READ).errors()).containsExactly("output: invalid JSON");
        assertThat(OutputSchema.parse(skill, "{\"sections\":{}}", 1, READ).errors())
                .contains("sections.decisions: missing", "sections.summary: missing");
    }

    @Test
    void check_whenLimitsClosedValuesOrForbiddenPhrasesAreBroken_reportsEach() {
        Map<String, String> cells = Map.of("decision", "x".repeat(201), "level", "Kritik");
        SkillOutput output = new SkillOutput(List.of(
                new SkillOutput.Section("decisions", null, null, null, java.util.Collections.nCopies(6,
                        new SkillOutput.Row(cells, List.of(1), false, List.of()))),
                new SkillOutput.Section("summary", "Bu kesinlikle doğrudur.", List.of(1), false, null)));
        List<String> errors = OutputSchema.check(skill, output, 1).errors();
        assertThat(errors).contains("sections.decisions.rows: 0..5 rows",
                "sections.decisions.rows[0].decision: at most 200 characters",
                "sections.decisions.rows[0].level: must be one of the allowed values",
                "output: contains a forbidden phrase");
    }

    @Test
    void check_whenNotAssessableRowAlsoCites_refuses() {
        SkillOutput output = new SkillOutput(List.of(
                new SkillOutput.Section("decisions", null, null, null, List.of(
                        new SkillOutput.Row(Map.of("decision", "A", "level", ""), List.of(1), true, List.of()))),
                new SkillOutput.Section("summary", "Özet.", List.of(1), false, null)));
        assertThat(OutputSchema.check(skill, output, 1).errors())
                .containsExactly("sections.decisions.rows[0].evidence: \"değerlendirilemez\" has no evidence");
    }

    @Test
    void anchors_whenRowStatesANumberOrCodeItsSourcesLack_listsThem() {
        Source passage = new Source(1, SkillCitationKind.PASSAGE, UUID.randomUUID(), UUID.randomUUID(), "a.pdf", 1,
                "PAGE", null, null, null, null, null, "Masraf limiti 50.000 TL'dir; onay kodu SAT-E058.");
        SkillOutput output = new SkillOutput(List.of(
                new SkillOutput.Section("decisions", null, null, null, List.of(
                        new SkillOutput.Row(Map.of("decision", "Limit 50000 TL, kod SAT-E058", "level", "Orta"),
                                List.of(1), false, List.of()),
                        new SkillOutput.Row(Map.of("decision", "Limit 75.000 TL, kod SAT-E999", "level", "Orta"),
                                List.of(1), false, List.of()),
                        new SkillOutput.Row(Map.of("decision", "Toplantı 14 Kasım", "level", "Orta"),
                                List.of(1), false, List.of()),
                        new SkillOutput.Row(Map.of("decision", "Tahmin 99", "level", ""), List.of(), true, List.of()))),
                new SkillOutput.Section("summary", "Özet 123.", List.of(1), false, null)));
        Anchors.Result r = Anchors.check(output, List.of(passage), "Toplantı 14 Kasım'da yapıldı.");
        List<SkillOutput.Row> rows = r.output().sections().getFirst().rows();
        assertThat(rows.get(0).unverified()).isEmpty();
        assertThat(rows.get(1).unverified()).containsExactly("75.000", "SAT-E999");
        assertThat(rows.get(2).unverified()).as("a number the user wrote may be repeated").isEmpty();
        assertThat(rows.get(3).unverified()).as("değerlendirilemez rows are not checked").isEmpty();
        assertThat(r.unverifiedRows()).isEqualTo(2);
    }

    @Test
    void fittingOutput_forTheTestSkill_isValid() {
        assertThat(OutputSchema.check(skill, TestSkills.fitting(skill, 1), 1).errors()).isEmpty();
        assertThat(OutputSchema.check(skill, TestSkills.fitting(skill, 0), 0).errors()).isEmpty();
    }
}
