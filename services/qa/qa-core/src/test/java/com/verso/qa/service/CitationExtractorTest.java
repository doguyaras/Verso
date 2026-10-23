package com.verso.qa.service;

import static com.verso.qa.service.CitationExtractor.EXCERPT_MAX;
import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.CitationUnit;
import com.verso.qa.service.CitationExtractor.Extracted;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** llm-rules 3.3: only numbers of retrieved passages become citations; everything else disappears from the text. */
class CitationExtractorTest {

    private static final UUID DOC_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID DOC_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final List<RetrievedPassage> PASSAGES = List.of(
            new RetrievedPassage(DOC_A, "a.pdf", 3, SourceUnit.PAGE, "first", 0.9),
            new RetrievedPassage(DOC_B, "b.docx", 7, SourceUnit.SECTION, "second", 0.8));

    private final CitationExtractor extractor = new CitationExtractor();

    @Test
    void extract_whenMarkersReferToRetrievedPassages_mapsThemInOrderOfFirstUse() {
        Extracted result = extractor.extract("B says so [2]. A agrees [1]. Again [2].", PASSAGES);
        assertThat(result.citations()).containsExactly(new Citation(2, DOC_B, "b.docx", 7, CitationUnit.SECTION, "second"),
                new Citation(1, DOC_A, "a.pdf", 3, CitationUnit.PAGE, "first"));
        assertThat(result.answer()).isEqualTo("B says so [2]. A agrees [1]. Again [2].");
    }

    @Test
    void extract_whenAMarkerIsInvented_removesItAndNeverCitesIt() {
        Extracted result = extractor.extract("Invented [9]. Zero [0]. Real [1].", PASSAGES);
        assertThat(result.citations()).extracting(Citation::number).containsExactly(1);
        assertThat(result.answer()).isEqualTo("Invented. Zero. Real [1].");
    }

    @Test
    void extract_whenAMarkerListsSeveralNumbers_keepsOnlyTheValidOnes() {
        Extracted result = extractor.extract("Both [1, 2, 5].", PASSAGES);
        assertThat(result.citations()).extracting(Citation::number).containsExactly(1, 2);
        assertThat(result.answer()).isEqualTo("Both [1][2].");
    }

    @Test
    void extract_whenThereIsNoMarker_hasNoCitations() {
        Extracted result = extractor.extract("Belgelerde bu sorunun cevabı bulunamadı.", PASSAGES);
        assertThat(result.citations()).isEmpty();
        assertThat(result.answer()).isEqualTo("Belgelerde bu sorunun cevabı bulunamadı.");
    }

    @Test
    void extract_whenThePassageIsLong_citesABoundedExcerptOfItsStart() {
        List<RetrievedPassage> passages = List.of(new RetrievedPassage(DOC_A, "a.pdf", 1, SourceUnit.PAGE,
                "Yıllık\n\tizin " + "kural ".repeat(200), 0.9));
        String excerpt = extractor.extract("Evet [1].", passages).citations().getFirst().excerpt();
        assertThat(excerpt).startsWith("Yıllık izin kural").endsWith("kural…");
        assertThat(excerpt.codePointCount(0, excerpt.length())).isLessThanOrEqualTo(EXCERPT_MAX);
    }

    @Test
    void excerpt_whenContentIsNullOrBlank_isEmpty() {
        assertThat(CitationExtractor.excerpt(null)).isEmpty();
        assertThat(CitationExtractor.excerpt("")).isEmpty();
        assertThat(CitationExtractor.excerpt(" \n\t   ")).isEmpty();
    }

    @Test
    void excerpt_whenWhiteSpaceIsMixed_collapsesItToSingleSpaces() {
        assertThat(CitationExtractor.excerpt("  a\r\n\tb  c d\u0000e  ")).isEqualTo("a b c d e");
    }

    @Test
    void excerpt_whenExactlyAtTheLimit_isKeptWholeWithoutEllipsis() {
        String words = "abc ".repeat(69) + "abcd";
        assertThat(words).hasSize(EXCERPT_MAX);
        assertThat(CitationExtractor.excerpt(words)).isEqualTo(words);
        assertThat(CitationExtractor.excerpt("x".repeat(EXCERPT_MAX))).isEqualTo("x".repeat(EXCERPT_MAX));
    }

    @Test
    void excerpt_whenOneOverTheLimitAtAWordEnd_keepsWholeWordsAndAddsEllipsis() {
        assertThat(CitationExtractor.excerpt("abc ".repeat(70) + "x"))
                .isEqualTo("abc ".repeat(69) + "abc…").hasSize(EXCERPT_MAX);
    }

    @Test
    void excerpt_whenTheLimitFallsInsideAWord_cutsBackToTheLastSpace() {
        assertThat(CitationExtractor.excerpt("abcde ".repeat(60))).isEqualTo("abcde ".repeat(45) + "abcde…");
    }

    @Test
    void excerpt_whenOneWordIsLongerThanTheLimit_cutsItHard() {
        assertThat(CitationExtractor.excerpt("x".repeat(1000))).isEqualTo("x".repeat(EXCERPT_MAX - 1) + "…");
        // The only space is too early: going back to it would drop almost everything.
        assertThat(CitationExtractor.excerpt("short " + "y".repeat(1000)))
                .isEqualTo("short " + "y".repeat(EXCERPT_MAX - 7) + "…").hasSize(EXCERPT_MAX);
    }

    @Test
    void excerpt_whenTurkish_keepsEveryLetter() {
        assertThat(CitationExtractor.excerpt("  Çalışanın\n yıllık  izni: İĞÜŞÖÇ ığüşöç  "))
                .isEqualTo("Çalışanın yıllık izni: İĞÜŞÖÇ ığüşöç");
        assertThat(CitationExtractor.excerpt("ğüşıöç ".repeat(60))).isEqualTo("ğüşıöç ".repeat(39) + "ğüşıöç…");
    }

    @Test
    void excerpt_whenTheLimitFallsInsideASurrogatePair_neverSplitsIt() {
        String cut = CitationExtractor.excerpt("a".repeat(EXCERPT_MAX - 2) + "😀".repeat(3));
        assertThat(cut).isEqualTo("a".repeat(EXCERPT_MAX - 2) + "😀…");
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(EXCERPT_MAX);
        assertThat(cut.codePoints().noneMatch(cp -> cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE))
                .as("no lone surrogate").isTrue();
    }
}
