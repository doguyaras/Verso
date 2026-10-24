package com.verso.connector.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Path traversal safety of ADR-0038: nothing outside the configured prefix can be addressed. */
class SourcePathsTest {

    @Test
    void normalizePrefix_whenGivenEitherSeparatorAndSurroundingSlashes_returnsTheNormalForm() {
        assertThat(SourcePaths.normalizePrefix(null)).isEmpty();
        assertThat(SourcePaths.normalizePrefix("  ")).isEmpty();
        assertThat(SourcePaths.normalizePrefix("/")).isEmpty();
        assertThat(SourcePaths.normalizePrefix("\\IK\\Politikalar\\")).isEqualTo("IK/Politikalar");
        assertThat(SourcePaths.normalizePrefix("Hukuk/Sözleşmeler")).isEqualTo("Hukuk/Sözleşmeler");
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "../etc", "a/../../b", "a/./b", "a//b", "C:\\Windows", "a:stream", "//server/share",
            "\\\\server\\share", "a/b\u0000c", "a/*", "a/b?", "a/<b>", "a/b|c", "a/\"b\"", "a/b./c", "a/b /c", "a/\u202Eb"})
    void normalizePrefix_whenTheInputCouldLeaveThePrefix_isRefused(String raw) {
        assertThatThrownBy(() -> SourcePaths.normalizePrefix(raw)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(raw);
    }

    @Test
    void normalizePrefix_whenTooLong_isRefused() {
        assertThatThrownBy(() -> SourcePaths.normalizePrefix("a/".repeat(600))).hasMessage("PATH_TOO_LONG");
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "", "a/b", "a\\b", "c:", "x:$DATA", "a\tb", "trailing.", "trailing "})
    void isSafeName_whenTheServerSendsAnUnsafeName_isFalse(String name) {
        assertThat(SourcePaths.isSafeName(name)).isFalse();
    }

    @Test
    void isSafeName_whenAnOrdinaryName_isTrue() {
        assertThat(SourcePaths.isSafeName("Rapor 2026 (son).pdf")).isTrue();
        assertThat(SourcePaths.isSafeName("çalışan-el-kitabı.docx")).isTrue();
        assertThat(SourcePaths.isSafeName("..hidden")).isTrue();
    }

    @Test
    void toShare_whenJoiningPrefixAndPath_usesBackslashes_andRevalidatesEverySegment() {
        assertThat(SourcePaths.toShare("", "")).isEmpty();
        assertThat(SourcePaths.toShare("IK/Politikalar", "2026/izin.pdf")).isEqualTo("IK\\Politikalar\\2026\\izin.pdf");
        assertThatThrownBy(() -> SourcePaths.toShare("IK", "../Finans/maaslar.xlsx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourcePaths.toShare("IK", "a\\..\\..\\b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void child_whenNameIsUnsafe_isRefused() {
        assertThat(SourcePaths.child("", "a.pdf")).isEqualTo("a.pdf");
        assertThat(SourcePaths.child("x", "a.pdf")).isEqualTo("x/a.pdf");
        assertThatThrownBy(() -> SourcePaths.child("x", "..")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isCandidate_whenTheExtensionIsReadByThePipeline_isTrue_andLockFilesNever() {
        assertThat(SourcePaths.isCandidate("a.PDF")).isTrue();
        assertThat(SourcePaths.isCandidate("a.docx")).isTrue();
        assertThat(SourcePaths.isCandidate("notes.md")).isTrue();
        assertThat(SourcePaths.isCandidate("notes.markdown")).isTrue();
        assertThat(SourcePaths.isCandidate("a.txt")).isTrue();
        assertThat(SourcePaths.isCandidate("~$a.docx")).isFalse();
        assertThat(SourcePaths.isCandidate("a.xlsx")).isFalse();
        assertThat(SourcePaths.isCandidate("pdf")).isFalse();
        assertThat(SourcePaths.isCandidate(".pdf")).isFalse();
    }
}
