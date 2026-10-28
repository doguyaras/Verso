package com.verso.work.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillOutput;
import com.verso.work.api.enums.SkillCategory;
import com.verso.work.domain.OutputSchema;
import com.verso.work.domain.PromptComposer;
import com.verso.work.domain.SkillRules;
import com.verso.work.domain.TestSkills;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every skill shipped in {@code skills/} (ADR-0057) loads with the strict reader, passes the definition rules, and runs
 * through the deterministic part of the pipeline with a stub model's answer: prompts for every step, the output
 * contract, parsing, checks. The golden set of each skill exists (the eval harness runs it against a real model).
 */
class RepoSkillsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path REPO_SKILLS = Path.of("../../../skills");

    private final BuiltInSkills skills = BuiltInSkills.load("classpath*:skills/*.json");

    @Test
    void repoSkills_whenLoaded_areTheTenP1SkillsAndAllValid() {
        assertThat(skills.all()).extracting(SkillDefinition::id).containsExactlyInAnyOrder("toplanti-karar-aksiyon",
                "kaynakli-rapor", "yazisma-ozeti", "ihale-yanit", "sozlesme-on-inceleme", "gereksinim-kalitesi",
                "proje-saglik", "premortem", "musteri-sikayet-analizi", "sop-taslagi");
        for (SkillDefinition d : skills.all()) {
            assertThat(SkillRules.validate(d, true)).as(d.id()).isEmpty();
            assertThat(d.disclaimer()).as(d.id()).isNotBlank();
            assertThat(d.rubric()).as(d.id()).hasSizeGreaterThanOrEqualTo(4);
            assertThat(d.framework().reference()).as(d.id()).isNotBlank();
        }
        assertThat(SkillRules.uniqueness(skills.all())).isEmpty();
    }

    @Test
    void complianceSkills_whenLoaded_carryTheReviewLabelAndForbiddenPhrases() {
        List<SkillDefinition> compliance = skills.all().stream()
                .filter(d -> d.category() == SkillCategory.LEGAL_COMPLIANCE || Boolean.TRUE.equals(d.compliance()))
                .toList();
        assertThat(compliance).isNotEmpty();
        for (SkillDefinition d : compliance) {
            assertThat(d.compliance()).as(d.id()).isTrue();
            assertThat(d.disclaimer()).as(d.id()).containsIgnoringCase("inceleme");
            assertThat(d.forbiddenPhrases()).as(d.id()).isNotEmpty();
        }
    }

    @Test
    void attributions_whenPresent_areOpenLicencesAndNeverTheSourceAvailableSkills() {
        for (SkillDefinition d : skills.all()) {
            if (d.attribution() == null) continue;
            assertThat(SkillRules.LICENSES).as(d.id()).contains(d.attribution().license());
            assertThat(d.attribution().source()).as(d.id()).isNotEqualTo("anthropics/skills");
        }
    }

    @Test
    void everySkill_withAStubModelAnswer_passesPromptsParsingAndChecks() {
        for (SkillDefinition d : skills.all()) {
            Map<String, Object> values = new LinkedHashMap<>();
            d.inputs().forEach(in -> {
                switch (in.type()) {
                    case TEXT, LONG_TEXT -> values.put(in.key(), "Örnek girdi metni.");
                    case SELECT -> values.put(in.key(), in.options().getFirst().value());
                    default -> { }
                }
            });
            for (int i = 0; i < d.steps().size(); i++) {
                PromptComposer.Prompt p = PromptComposer.step(d, i, values, List.of(), List.of());
                assertThat(p.user()).as(d.id() + " step " + i).doesNotContain("{{");
                if (i == d.steps().size() - 1) {
                    d.output().sections().forEach(s -> assertThat(p.system()).as(d.id()).contains("\"" + s.key() + "\""));
                }
            }
            for (int sources : new int[]{0, 1}) {
                String answer = JSON.writeValueAsString(modelJson(TestSkills.fitting(d, sources)));
                OutputSchema.Checked c = OutputSchema.parse(d, "```json\n" + answer + "\n```", sources,
                        t -> JSON.readValue(t, Object.class));
                assertThat(c.errors()).as(d.id() + " sources=" + sources).isEmpty();
            }
        }
    }

    @Test
    void everySkill_hasAGoldenSet() {
        for (SkillDefinition d : skills.all()) {
            assertThat(REPO_SKILLS.resolve("golden/" + d.id() + ".json")).as(d.id()).exists();
        }
        assertThat(Files.exists(REPO_SKILLS.resolve("README.md"))).isTrue();
    }

    @Test
    void load_whenAFileHasAnUnknownField_refusesToStart() {
        assertThatThrownBy(() -> BuiltInSkills.load("classpath*:bad-skills/*.json"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unreadable");
    }

    /** The model's shape of an output: sections by key, rows with their cells and evidence. */
    private static Map<String, Object> modelJson(SkillOutput output) {
        Map<String, Object> sections = new LinkedHashMap<>();
        for (SkillOutput.Section s : output.sections()) {
            Object evidenceOfText = Boolean.TRUE.equals(s.notAssessable()) ? SkillOutput.NOT_ASSESSABLE : s.evidence();
            if (s.rows() == null) {
                sections.put(s.key(), Map.of("text", s.text(), "evidence", evidenceOfText));
            } else {
                sections.put(s.key(), Map.of("rows", s.rows().stream().map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>(r.cells());
                    row.put("evidence", Boolean.TRUE.equals(r.notAssessable()) ? SkillOutput.NOT_ASSESSABLE : r.evidence());
                    return row;
                }).toList()));
            }
        }
        return Map.of("sections", sections);
    }
}
