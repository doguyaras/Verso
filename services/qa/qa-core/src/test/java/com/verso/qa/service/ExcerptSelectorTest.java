package com.verso.qa.service;

import static com.verso.qa.service.ExcerptSelector.MAX;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * ADR-0020: the excerpt is the passage window that best supports the citing answer sentences, always passage text and
 * at most {@link ExcerptSelector#MAX} code points; without overlap it is the passage start.
 */
class ExcerptSelectorTest {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    private static final String F1 ="Bu yönetmelik şirketin bütün birimlerinde uygulanır.";
    private static final String F2 = "İnsan kaynakları müdürlüğü kayıtları elektronik ortamda saklar.";
    private static final String F3 = "Değişiklikler yönetim kurulunun onayıyla yürürlüğe girer.";
    private static final String F4 = "Personel kartı kaybolursa güvenlik masasına haber verilir.";
    private static final String F5 = "Toplantı tutanakları arşivde beş yıl tutulur.";
    private static final String LEAVE = "Çalışanlara yılda on dört iş günü ücretli yıllık izin verilir.";
    private static final String SPLIT = "İzin en fazla iki parçaya bölünebilir.";
    private static final String PASSAGE = String.join(" ", F1, F2, F3, LEAVE, SPLIT, F4, F5);

    private static int codePoints(String text) {
        return text.codePointCount(0, text.length());
    }

    /** llm-rules 3.3: every kept piece of the excerpt is passage text; only the ellipses are added. */
    private static void assertIsPassageText(String excerpt, String passage) {
        String collapsed = passage.replaceAll("\\s+", " ").strip();
        for (String piece : excerpt.split(ExcerptSelector.ELLIPSIS)) assertThat(collapsed).contains(piece);
        assertThat(codePoints(excerpt)).isLessThanOrEqualTo(MAX);
    }

    @Test
    void select_whenTheSupportingSentenceIsInTheMiddle_returnsThatSentence() {
        assertThat(codePoints(PASSAGE)).isGreaterThan(MAX);
        String excerpt = ExcerptSelector.select(PASSAGE, "Yıllık izin on dört iş günüdür.");
        // A window that starts at a sentence boundary is not cut, so it has no ellipsis.
        assertThat(excerpt).isEqualTo(LEAVE);
    }

    @Test
    void select_whenTheAnswerCombinesTwoAdjacentSentences_returnsBoth() {
        String excerpt = ExcerptSelector.select(PASSAGE, "Yıllık izin on dört iş günüdür ve iki parçaya bölünebilir.");
        assertThat(excerpt).isEqualTo(LEAVE + " " + SPLIT);
    }

    @Test
    void select_whenASecondSentenceAddsNoTerm_keepsTheSingleSentence() {
        // SPLIT shares "izin" with the answer, which LEAVE already has: the pair would score the same.
        assertThat(ExcerptSelector.select(PASSAGE, "Yıllık izin on dört gündür.")).isEqualTo(LEAVE);
    }

    @Test
    void select_whenTheAnswerIsUpperCaseOrWithoutTurkishLetters_stillMatches() {
        assertThat(ExcerptSelector.select(PASSAGE, "YILLIK İZİN ON DÖRT İŞ GÜNÜDÜR.")).isEqualTo(LEAVE);
        assertThat(ExcerptSelector.select(PASSAGE, "Yillik izin on dort is gunudur.")).isEqualTo(LEAVE);
        assertThat(ExcerptSelector.select(PASSAGE.toUpperCase(TURKISH), "yıllık izin on dört iş günüdür"))
                .isEqualTo(LEAVE.toUpperCase(TURKISH));
    }

    @Test
    void fold_whenTurkishCasingDiffers_givesOneForm() {
        assertThat(ExcerptSelector.fold("İZİN")).isEqualTo("izin");
        assertThat(ExcerptSelector.fold("ızın")).isEqualTo("izin");
        assertThat(ExcerptSelector.fold("IŞIK")).isEqualTo("isik");
        assertThat(ExcerptSelector.fold("Çalışanlara")).isEqualTo("calisanlara");
        assertThat(ExcerptSelector.fold("Öğrenci Ücreti")).isEqualTo("ogrenci ucreti");
    }

    @Test
    void key_whenWordsShareAFiveLetterPrefix_match() {
        assertThat(ExcerptSelector.key("çalışanlara")).isEqualTo(ExcerptSelector.key("Çalışanın"));
        assertThat(ExcerptSelector.key("İzinlerin")).isEqualTo(ExcerptSelector.key("izinleri"));
        // Shorter words must be equal: "izin" and "izni" are different keys.
        assertThat(ExcerptSelector.key("izin")).isNotEqualTo(ExcerptSelector.key("izni"));
        assertThat(ExcerptSelector.key("ve")).isNull();
        assertThat(ExcerptSelector.key("x")).isNull();
    }

    @Test
    void key_whenNumbersAreWrittenWithOrWithoutSeparators_match() {
        assertThat(ExcerptSelector.key("25.000")).isEqualTo(ExcerptSelector.key("25000")).isEqualTo("#25000");
        assertThat(ExcerptSelector.key("3.2")).isEqualTo(ExcerptSelector.key("3,2"));
        assertThat(ExcerptSelector.terms("Avans 25.000 TL'dir")).containsEntry("#25000", ExcerptSelector.NUMBER_WEIGHT)
                .containsEntry("avans", 1);
    }

    @Test
    void select_whenOneSentenceSharesTheNumber_prefersItOverMoreSharedWords() {
        String limit = "Üst sınır her yıl yeniden belirlenir ve sınır hiçbir durumda aşılamaz.";
        String amount = "Avans tutarı en çok 25000 TL'dir.";
        String passage = String.join(" ", F1, limit, F2, F3, F4, amount, F5);
        assertThat(codePoints(passage)).isGreaterThan(MAX);
        // The limit sentence shares two words ("üst", "sınır"); the amount sentence the number and "TL".
        assertThat(ExcerptSelector.select(passage, "Üst sınır 25.000 TL.")).isEqualTo(amount);
    }

    @Test
    void select_whenNothingOverlaps_fallsBackToThePassageStart() {
        String excerpt = ExcerptSelector.select(PASSAGE, "Hava bugün güneşli.");
        assertThat(excerpt).startsWith(F1).endsWith("…").isEqualTo(ExcerptSelector.select(PASSAGE, ""));
        assertIsPassageText(excerpt, PASSAGE);
        // Only stop words or markers left: no evidence either.
        assertThat(ExcerptSelector.select(PASSAGE, "ve bu da")).isEqualTo(excerpt);
        assertThat(ExcerptSelector.select(PASSAGE, null)).isEqualTo(excerpt);
    }

    @Test
    void select_whenTwoSentencesScoreTheSame_takesTheEarlierOne() {
        String first = "Yıllık izin talebi yazılı yapılır.";
        String second = "Yıllık izin planı ocakta duyurulur.";
        String passage = String.join(" ", F1, F2, first, F3, F4, second, F5);
        assertThat(ExcerptSelector.select(passage, "Yıllık izin.")).isEqualTo(first);
    }

    @Test
    void select_whenTheSupportIsDeepInsideALongSentence_startsAtItWithAnEllipsis() {
        String filler = "hizmet süresine bağlı olarak değişen ve ayrıca kıdem yılı arttıkça uzayan ";
        String longSentence = "Çalışanın " + filler.repeat(5) + "izin süresi en az 14 gündür ve "
                + "takvim yılı içinde kullanılmayan günler bir sonraki yıla devredilebilir ".repeat(4) + "kuralı uygulanır.";
        String passage = F1 + " " + longSentence;
        String excerpt = ExcerptSelector.select(passage, "İzin süresi 14 gündür.");
        assertThat(excerpt).startsWith("…izin süresi en az 14 gündür").endsWith("…").doesNotContain(" …");
        assertIsPassageText(excerpt, passage);
    }

    @Test
    void select_whenTheSupportIsNearTheStartOfALongSentence_keepsTheSentenceStart() {
        String longSentence = "Yıllık izin 14 gündür ve " + "kullanılmayan günler bir sonraki yıla aktarılabilir ".repeat(8)
                + "denir.";
        String excerpt = ExcerptSelector.select(F1 + " " + longSentence, "Yıllık izin 14 gündür.");
        assertThat(excerpt).startsWith("Yıllık izin 14 gündür ve").endsWith("…");
        assertThat(codePoints(excerpt)).isLessThanOrEqualTo(MAX);
    }

    @Test
    void select_whenThePromptCarriedOnlyPartOfThePassage_usesEvidenceFromThatPartOnly() {
        int leaveStart = PASSAGE.indexOf(LEAVE);
        // The prompt ended inside LEAVE after "on dört": those words count, "iş günü … yıllık izin" do not.
        int seen = PASSAGE.indexOf("iş günü");
        assertThat(ExcerptSelector.select(PASSAGE, "Yıllık izin on dört iş günüdür.", seen)).isEqualTo(LEAVE);
        // Ended before LEAVE: nothing the model saw overlaps, so the excerpt is the passage start.
        assertThat(ExcerptSelector.select(PASSAGE, "Yıllık izin on dört iş günüdür.", leaveStart))
                .isEqualTo(ExcerptSelector.select(PASSAGE, ""));
        // A limit past the end is the whole passage.
        assertThat(ExcerptSelector.select(PASSAGE, "Yıllık izin on dört iş günüdür.", PASSAGE.length() + 10))
                .isEqualTo(LEAVE);
    }

    @Test
    void select_whenThePassageFits_returnsItWhole() {
        assertThat(ExcerptSelector.select("  Kısa\n pasaj.  İkinci cümle. ", "ikinci")).isEqualTo("Kısa pasaj. İkinci cümle.");
    }

    // The trim rules of the fallback (the passage start), unchanged from before ADR-0020.

    @Test
    void select_whenContentIsNullOrBlank_isEmpty() {
        assertThat(ExcerptSelector.select(null, "izin")).isEmpty();
        assertThat(ExcerptSelector.select("", "izin")).isEmpty();
        assertThat(ExcerptSelector.select(" \n\t   ", "izin")).isEmpty();
    }

    @Test
    void select_whenWhiteSpaceIsMixed_collapsesItToSingleSpaces() {
        assertThat(ExcerptSelector.select("  a\r\n\tb  c d\u0000e  ", "")).isEqualTo("a b c d e");
    }

    @Test
    void select_whenExactlyAtTheLimit_isKeptWholeWithoutEllipsis() {
        String words = "abc ".repeat(69) + "abcd";
        assertThat(words).hasSize(MAX);
        assertThat(ExcerptSelector.select(words, "")).isEqualTo(words);
        assertThat(ExcerptSelector.select("x".repeat(MAX), "")).isEqualTo("x".repeat(MAX));
    }

    @Test
    void select_whenOneOverTheLimitAtAWordEnd_keepsWholeWordsAndAddsEllipsis() {
        assertThat(ExcerptSelector.select("abc ".repeat(70) + "x", "")).isEqualTo("abc ".repeat(69) + "abc…").hasSize(MAX);
    }

    @Test
    void select_whenTheLimitFallsInsideAWord_cutsBackToTheLastSpace() {
        assertThat(ExcerptSelector.select("abcde ".repeat(60), "")).isEqualTo("abcde ".repeat(45) + "abcde…");
    }

    @Test
    void select_whenOneWordIsLongerThanTheLimit_cutsItHard() {
        assertThat(ExcerptSelector.select("x".repeat(1000), "")).isEqualTo("x".repeat(MAX - 1) + "…");
        // The only space is too early: going back to it would drop almost everything.
        assertThat(ExcerptSelector.select("short " + "y".repeat(1000), ""))
                .isEqualTo("short " + "y".repeat(MAX - 7) + "…").hasSize(MAX);
    }

    @Test
    void select_whenTurkish_keepsEveryLetter() {
        assertThat(ExcerptSelector.select("  Çalışanın\n yıllık  izni: İĞÜŞÖÇ ığüşöç  ", ""))
                .isEqualTo("Çalışanın yıllık izni: İĞÜŞÖÇ ığüşöç");
        assertThat(ExcerptSelector.select("ğüşıöç ".repeat(60), "")).isEqualTo("ğüşıöç ".repeat(39) + "ğüşıöç…");
    }

    @Test
    void select_whenTheLimitFallsInsideASurrogatePair_neverSplitsIt() {
        String cut = ExcerptSelector.select("a".repeat(MAX - 2) + "😀".repeat(3), "");
        assertThat(cut).isEqualTo("a".repeat(MAX - 2) + "😀…");
        assertThat(codePoints(cut)).isEqualTo(MAX);
        assertThat(cut.codePoints().noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE))
                .as("no lone surrogate").isTrue();
    }
}
