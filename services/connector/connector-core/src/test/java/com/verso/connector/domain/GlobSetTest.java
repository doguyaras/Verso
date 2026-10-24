package com.verso.connector.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Include and exclude patterns of ADR-0038. */
class GlobSetTest {

    @Test
    void matches_whenThePatternHasNoSlash_matchesTheFileNameInAnyFolder() {
        GlobSet pdf = GlobSet.compile(List.of("*.pdf"));

        assertThat(pdf.matches("a.pdf")).isTrue();
        assertThat(pdf.matches("deep/er/A.PDF")).as("case-insensitive like SMB").isTrue();
        assertThat(pdf.matches("a.pdf.txt")).isFalse();
    }

    @Test
    void matches_whenThePatternHasASlash_matchesTheWholePath() {
        GlobSet hr = GlobSet.compile(List.of("ik/**"));
        GlobSet drafts = GlobSet.compile(List.of("**/taslak/*.docx"));

        assertThat(hr.matches("ik/a.pdf")).isTrue();
        assertThat(hr.matches("ik/2026/a.pdf")).isTrue();
        assertThat(hr.matches("finans/ik/a.pdf")).isFalse();
        assertThat(drafts.matches("taslak/a.docx")).as("**/ also matches no folder").isTrue();
        assertThat(drafts.matches("x/y/taslak/a.docx")).isTrue();
        assertThat(drafts.matches("x/taslak/y/a.docx")).isFalse();
    }

    @Test
    void matches_whenAlternativesAndSingleCharacters_areUsed_matchesEither() {
        GlobSet set = GlobSet.compile(List.of("rapor-202?.{pdf,docx}"));

        assertThat(set.matches("rapor-2026.pdf")).isTrue();
        assertThat(set.matches("x/rapor-2025.docx")).isTrue();
        assertThat(set.matches("rapor-20261.pdf")).isFalse();
        assertThat(set.matches("rapor-2026.md")).isFalse();
    }

    @Test
    void matches_whenThePatternHasRegexCharacters_treatsThemLiterally() {
        GlobSet set = GlobSet.compile(List.of("a+b (1).pdf"));

        assertThat(set.matches("a+b (1).pdf")).isTrue();
        assertThat(set.matches("aab (1).pdf")).isFalse();
    }

    @Test
    void compile_whenAPatternIsUnusable_isRefused() {
        for (String bad : new String[]{"", " ", "{a,b", "a}", "{a,{b}}", "../x/*.pdf", "x".repeat(201), "*a*a*a*a*a*a*a"}) {
            assertThatThrownBy(() -> GlobSet.compile(List.of(bad))).as(bad).hasMessage("GLOB_INVALID");
        }
        assertThat(GlobSet.compile(null).isEmpty()).isTrue();
    }
}
