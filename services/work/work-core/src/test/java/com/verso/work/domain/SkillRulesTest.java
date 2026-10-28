package com.verso.work.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillDefinition.Step;
import com.verso.work.api.enums.SkillCategory;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The definition rules of ADR-0057: what a repository skill and an admin's custom skill must satisfy. */
class SkillRulesTest {

    @Test
    void validate_whenDefinitionIsComplete_reportsNothing() {
        assertThat(SkillRules.validate(TestSkills.valid(), true)).isEmpty();
    }

    @Test
    void validate_whenRequiredFieldsAreMissing_namesEachPathWithoutValues() {
        SkillDefinition d = TestSkills.valid();
        SkillDefinition broken = new SkillDefinition(null, "Bad Id", "1", null, "kısa", null, "/x", List.of(), null,
                List.of(), null, 10, List.of(), null, null, null, null, null);
        List<String> errors = SkillRules.validate(broken, true);
        assertThat(errors).anyMatch(e -> e.startsWith("formatVersion"))
                .anyMatch(e -> e.startsWith("id:")).anyMatch(e -> e.startsWith("version:"))
                .anyMatch(e -> e.startsWith("name:")).anyMatch(e -> e.startsWith("description:"))
                .anyMatch(e -> e.startsWith("category:")).anyMatch(e -> e.startsWith("slash:"))
                .anyMatch(e -> e.startsWith("inputs:")).anyMatch(e -> e.startsWith("steps:"))
                .anyMatch(e -> e.startsWith("output.sections:")).anyMatch(e -> e.startsWith("maxOutputChars:"))
                .anyMatch(e -> e.startsWith("rubric:")).anyMatch(e -> e.startsWith("framework:"))
                .anyMatch(e -> e.startsWith("disclaimer:")).anyMatch(e -> e.startsWith("compliance:"));
        assertThat(errors).noneMatch(e -> e.contains("Bad Id") || e.contains("kısa"));
        assertThat(d.id()).isEqualTo("test-skill");
    }

    @Test
    void validate_whenMoreThanFourSteps_refuses() {
        SkillDefinition d = TestSkills.valid();
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 4; i++) steps.add(new Step("s" + i, "Adım", "Bir şey yap ve not yaz, kısa tut.", 500));
        steps.add(new Step("son", "Son", "Tabloyu yaz ve kaynak göster.", null));
        assertThat(SkillRules.validate(with(d, steps), true)).contains("steps: 1..4 steps");
    }

    @Test
    void validate_whenLengthCapsAreOutOfRange_refuses() {
        SkillDefinition d = TestSkills.valid();
        SkillDefinition big = new SkillDefinition(1, d.id(), d.version(), d.name(), d.description(), d.category(),
                d.slash(), d.inputs(), d.grounding(), d.steps(), d.output(), 40_000, d.rubric(), d.framework(),
                d.disclaimer(), d.compliance(), d.attribution(), d.forbiddenPhrases());
        assertThat(SkillRules.validate(big, true)).anyMatch(e -> e.startsWith("maxOutputChars"));
        List<Step> steps = List.of(new Step("a", "A", "Notları oku ve kısa not yaz.", 50),
                new Step("b", "B", "Tabloyu yaz ve kaynak göster.", 100));
        assertThat(SkillRules.validate(with(d, steps), true))
                .contains("steps[0].maxChars: must be 200..6000")
                .contains("steps[1].maxChars: the last step is capped by maxOutputChars");
    }

    @Test
    void validate_whenComplianceSkillLacksReviewLabelOrForbiddenPhrases_refuses() {
        SkillDefinition d = TestSkills.valid();
        SkillDefinition legal = new SkillDefinition(1, d.id(), d.version(), d.name(), d.description(),
                SkillCategory.LEGAL_COMPLIANCE, d.slash(), d.inputs(), d.grounding(), d.steps(), d.output(),
                d.maxOutputChars(), d.rubric(), d.framework(), "Taslak çıktı, dikkatle kullanın.", false, null, List.of());
        assertThat(SkillRules.validate(legal, true))
                .contains("compliance: must be true for LEGAL_COMPLIANCE");
        SkillDefinition flagged = new SkillDefinition(1, d.id(), d.version(), d.name(), d.description(),
                SkillCategory.LEGAL_COMPLIANCE, d.slash(), d.inputs(), d.grounding(), d.steps(), d.output(),
                d.maxOutputChars(), d.rubric(), d.framework(), "Taslak çıktı, dikkatle kullanın.", true, null, List.of());
        assertThat(SkillRules.validate(flagged, true))
                .anyMatch(e -> e.startsWith("disclaimer: a compliance skill"))
                .anyMatch(e -> e.startsWith("forbiddenPhrases: a compliance skill"));
    }

    @Test
    void validate_whenAttributionIsSourceAvailableOrUnknownLicence_refuses() {
        SkillDefinition d = TestSkills.valid();
        SkillDefinition copied = new SkillDefinition(1, d.id(), d.version(), d.name(), d.description(), d.category(),
                d.slash(), d.inputs(), d.grounding(), d.steps(), d.output(), d.maxOutputChars(), d.rubric(),
                d.framework(), d.disclaimer(), false,
                new SkillDefinition.Attribution("anthropics/skills", "Proprietary", "https://github.com/anthropics/skills",
                        "docx"), null);
        assertThat(SkillRules.validate(copied, true))
                .anyMatch(e -> e.startsWith("attribution.license"))
                .contains("attribution.source: anthropics/skills content may not be adapted");
    }

    @Test
    void validate_whenRepoSkillRestrictsCollectionsOrPlaceholderNamesAnIdInput_refuses() {
        SkillDefinition d = TestSkills.valid();
        SkillDefinition scoped = new SkillDefinition(1, d.id(), d.version(), d.name(), d.description(), d.category(),
                d.slash(), d.inputs(), new SkillDefinition.Grounding(List.of("{{docs}} {{missing}}"), 4, "notes", null,
                List.of(UUID.randomUUID())), d.steps(), d.output(), d.maxOutputChars(), d.rubric(), d.framework(),
                d.disclaimer(), false, null, null);
        assertThat(SkillRules.validate(scoped, true))
                .contains("grounding.scopeCollections: only customer-authored skills restrict collections")
                .contains("grounding.queries[0]: {{docs}} must name a text or select input")
                .contains("grounding.queries[0]: {{missing}} must name a text or select input")
                .contains("grounding.documentsInput: must name a DOCUMENTS input");
        assertThat(SkillRules.validate(scoped, false)).doesNotContain(
                "grounding.scopeCollections: only customer-authored skills restrict collections");
    }

    @Test
    void validate_whenRubricLacksGroundednessOrLength_refuses() {
        SkillDefinition d = TestSkills.valid();
        SkillDefinition noRubric = new SkillDefinition(1, d.id(), d.version(), d.name(), d.description(), d.category(),
                d.slash(), d.inputs(), d.grounding(), d.steps(), d.output(), d.maxOutputChars(),
                List.of(new SkillDefinition.RubricDimension("coverage", "Kapsam", "Bütün kararlar listelenir."),
                        new SkillDefinition.RubricDimension("specificity", "Özgüllük", "Genel tavsiye vermez."),
                        new SkillDefinition.RubricDimension("actionability", "Uygulanabilirlik", "Sahip ve tarih var.")),
                d.framework(), d.disclaimer(), false, null, null);
        assertThat(SkillRules.validate(noRubric, true))
                .contains("rubric: needs a \"groundedness\" dimension", "rubric: needs a \"length\" dimension");
    }

    @Test
    void uniqueness_whenTwoSkillsShareASlash_reportsIt() {
        assertThat(SkillRules.uniqueness(List.of(TestSkills.valid(), TestSkills.valid())))
                .contains("id: test-skill is used 2 times", "slash: test is used 2 times");
    }

    private static SkillDefinition with(SkillDefinition d, List<Step> steps) {
        return new SkillDefinition(1, d.id(), d.version(), d.name(), d.description(), d.category(), d.slash(),
                d.inputs(), d.grounding(), steps, d.output(), d.maxOutputChars(), d.rubric(), d.framework(),
                d.disclaimer(), d.compliance(), d.attribution(), d.forbiddenPhrases());
    }
}
