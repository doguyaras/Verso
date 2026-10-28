package com.verso.work.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.work.api.dto.SkillDefinition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The form check of a run (ADR-0057): types, required fields, limits, options; errors never quote a value. */
class InputRulesTest {

    private final SkillDefinition skill = TestSkills.valid();

    @Test
    void check_whenValuesFit_normalizesThem() {
        UUID doc = UUID.randomUUID();
        InputRules.Result r = InputRules.check(skill, Map.of("notes", "  Karar: bütçe onaylandı.\u0000\r\n ",
                "tone", "sade", "docs", List.of(doc.toString().toUpperCase(), doc.toString())));
        assertThat(r.valid()).isTrue();
        assertThat(r.values()).containsEntry("notes", "Karar: bütçe onaylandı.").containsEntry("tone", "sade")
                .containsEntry("docs", List.of(doc.toString()));
    }

    @Test
    void check_whenRequiredMissingOrTooLong_refusesWithoutTheValue() {
        assertThat(InputRules.check(skill, Map.of()).errors()).containsExactly("notes: required");
        Map<String, Object> values = new HashMap<>();
        values.put("notes", "MarkerSecret ".repeat(1000));
        InputRules.Result r = InputRules.check(skill, values);
        assertThat(r.errors()).containsExactly("notes: at most 5000 characters");
        assertThat(String.join(" ", r.errors())).doesNotContain("MarkerSecret");
    }

    @Test
    void check_whenTypesOptionsOrIdsAreWrong_refuses() {
        InputRules.Result r = InputRules.check(skill, Map.of("notes", 42, "tone", "kaba",
                "docs", List.of("not-an-id"), "extra", "x"));
        assertThat(r.errors()).contains("inputs: unknown field", "notes: must be text", "tone: not one of the options",
                "docs: must be a list of ids");
        assertThat(r.values()).isEmpty();
    }

    @Test
    void check_whenTooManyIds_refuses() {
        List<String> ids = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
        assertThat(InputRules.check(skill, Map.of("notes", "a", "docs", ids)).errors())
                .containsExactly("docs: at most 3 items");
    }

    @Test
    void inputParts_whenLongTextIsLong_splitsAtLineBreaksWithOffsets() {
        String text = ("Satır bir karar içerir.\n").repeat(150);
        List<Source> parts = InputParts.split(skill, Map.of("notes", text), 3);
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(parts.getFirst().number()).isEqualTo(3);
        for (Source p : parts) {
            assertThat(p.text().length()).isLessThanOrEqualTo(InputParts.PART_CHARS);
            assertThat(text.substring(p.start(), p.end()).strip()).isEqualTo(p.text());
        }
        assertThat(parts.getFirst().toString()).doesNotContain("karar");
    }
}
