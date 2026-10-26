package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.qa.service.SkillMarkdown.Reference;
import com.verso.qa.service.SkillMarkdown.Step;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The SKILL.md export (ADR-0053, agentskills.io): frontmatter validity, citation links only, the 500-line cap. */
class SkillMarkdownTest {

    private static final UUID SKILL = UUID.fromString("0199aaaa-0000-7000-8000-000000000001");
    private static final UUID PASSAGE_1 = UUID.fromString("0199bbbb-0000-7000-8000-000000000001");
    private static final UUID PASSAGE_2 = UUID.fromString("0199bbbb-0000-7000-8000-000000000002");

    @Test
    void render_writesSpecFrontmatter_stepsWithVersoFetchLinks_andNoDocumentText() {
        String markdown = SkillMarkdown.render(SKILL, "vpn-kurulumu", "VPN kurulumu: 2 adımlı onaylı prosedür.", 3,
                Instant.parse("2026-10-26T10:00:00Z"), List.of("VPN nasıl kurulur?"),
                List.of(new Step("İstemciyi kurun.", List.of(new Reference(PASSAGE_1, "Belge 0199cccc, s. 1"))),
                        new Step("Port 443 ile bağlanın.", List.of(new Reference(PASSAGE_2, "Belge 0199cccc, s. 2"),
                                new Reference(PASSAGE_1, "Belge 0199cccc, s. 1")))));

        assertThat(markdown).startsWith("---\nname: vpn-kurulumu\ndescription: \"VPN kurulumu: 2 adımlı onaylı prosedür.\"\n"
                + "metadata:\n  verso-skill-id: \"" + SKILL + "\"\n  verso-version: \"3\"\n"
                + "  verso-approved-at: \"2026-10-26T10:00:00Z\"\n---\n");
        assertThat(markdown).contains("1. İstemciyi kurun. [1](verso://passages/" + PASSAGE_1 + ")")
                .contains("2. Port 443 ile bağlanın. [2](verso://passages/" + PASSAGE_2 + ") [1](verso://passages/"
                        + PASSAGE_1 + ")")
                .contains("- [2] `verso://passages/" + PASSAGE_2 + "`: Belge 0199cccc, s. 2")
                .contains("verso_fetch").contains("güvenilmeyen belge içeriğidir");
        assertThat(SkillMarkdown.validate(markdown)).isEmpty();
    }

    @Test
    void render_cannotBreakTheFrontmatterOrInjectMarkup() {
        String markdown = SkillMarkdown.render(SKILL, "x", "a \"quoted\" \\ text\n---\nname: evil", 1, null,
                List.of("[tıkla](https://evil.example) <script>"), List.of(new Step("# başlık | tablo", List.of())));
        assertThat(markdown).contains("description: \"a \\\"quoted\\\" \\\\ text --- name: evil\"\n");
        assertThat(markdown.split("\n---\n", -1)).as("the frontmatter ends once").hasSize(2);
        assertThat(markdown).contains("- \\[tıkla\\](https://evil.example) \\<script\\>")
                .contains("1. \\# başlık \\| tablo");
        assertThat(SkillMarkdown.validate(markdown)).isEmpty();
        assertThatThrownBy(() -> SkillMarkdown.render(SKILL, "Bad Name", "d", 1, null, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_checksNameDescriptionFrontmatterAndTheLineCap() {
        assertThat(SkillMarkdown.validate("no frontmatter")).containsExactly("NO_FRONTMATTER");
        assertThat(SkillMarkdown.validate("---\nname: x\n")).containsExactly("NO_FRONTMATTER");
        assertThat(SkillMarkdown.validate("---\nname: Bad\ndescription: \"d\"\n---\n")).containsExactly("NAME");
        assertThat(SkillMarkdown.validate("---\nname: ok\n---\n")).containsExactly("DESCRIPTION");
        assertThat(SkillMarkdown.validate("---\nname: ok\ndescription: \"" + "d".repeat(1025) + "\"\n---\n"))
                .containsExactly("DESCRIPTION");
        List<String> lines = new ArrayList<>(List.of("---", "name: ok", "description: \"d\"", "---"));
        lines.addAll(Collections.nCopies(497, "x"));
        assertThat(SkillMarkdown.validate(String.join("\n", lines) + "\n")).as("501 lines").containsExactly("TOO_LONG");
        assertThat(SkillMarkdown.validate(String.join("\n", lines.subList(0, 500)) + "\n")).isEmpty();
    }

    @Test
    void theLargestSkill_staysFarUnderFiveHundredLines() {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < SkillRules.MAX_STEPS; i++) {
            List<Reference> references = new ArrayList<>();
            for (int r = 0; r < 20; r++) references.add(new Reference(UUID.randomUUID(), "Belge x, s. " + r));
            steps.add(new Step("Adım " + i, references));
        }
        List<String> triggers = Collections.nCopies(SkillRules.MAX_TRIGGERS, "Soru?");
        String markdown = SkillMarkdown.render(SKILL, "buyuk", "d".repeat(1024), 9, Instant.EPOCH, triggers, steps);
        assertThat(markdown.split("\n").length).isLessThan(150);
        assertThat(SkillMarkdown.validate(markdown)).isEmpty();
        assertThat(markdown).as("at most five references per step").doesNotContain("Belge x, s. 5");
    }
}
