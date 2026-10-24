package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The grouping key of the k-anonymous list (ADR-0023): equal questions in Turkish spelling variants are one group. */
class QuestionNormalizerTest {

    @Test
    void normalize_whenCaseSpacesOrTrailingPunctuationDiffer_givesOneText() {
        assertThat(QuestionNormalizer.normalize("  KARGO   ÜCRETİ ne kadar ?! ")).isEqualTo("kargo ücreti ne kadar");
        assertThat(QuestionNormalizer.normalize("Kargo ücreti ne kadar")).isEqualTo("kargo ücreti ne kadar");
        // Turkish lower case: dotted capital İ becomes i, not "i̇" (i + combining dot).
        assertThat(QuestionNormalizer.normalize("İZİN")).isEqualTo("izin");
        // NFKC: a full-width letter is the letter.
        assertThat(QuestionNormalizer.normalize("ｉzin")).isEqualTo("izin");
        // Inner punctuation is part of the question.
        assertThat(QuestionNormalizer.normalize("1.5 gün mü?")).isEqualTo("1.5 gün mü");
        // Shown to admins: no bidi override or control character survives.
        assertThat(QuestionNormalizer.normalize("izin‮nedir\u0007?")).isEqualTo("izin nedir");
    }

    @Test
    void hash_whenQuestionsNormaliseEqually_isEqualAnd32Bytes() {
        assertThat(QuestionNormalizer.hash("Kargo ücreti?")).hasSize(32)
                .isEqualTo(QuestionNormalizer.hash("kargo   ÜCRETİ"));
        assertThat(QuestionNormalizer.hash("kargo ücreti")).isNotEqualTo(QuestionNormalizer.hash("kargo süresi"));
    }
}
