package com.verso.qa.service;

import static com.verso.qa.service.ExcerptSelector.MAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.SourceUnit;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.CitationUnit;
import com.verso.qa.config.QaProperties;
import com.verso.qa.service.CitationExtractor.Extracted;
import java.time.Duration;
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

    // Two long synthetic passages (ADR-0019): the supporting sentence is in the middle of each.
    private static final String FILLER = "Bu yönetmelik şirketin bütün birimlerinde uygulanır. İnsan kaynakları müdürlüğü "
            + "kayıtları elektronik ortamda saklar. Değişiklikler yönetim kurulunun onayıyla yürürlüğe girer.";
    private static final String TAIL = "Personel kartı kaybolursa güvenlik masasına haber verilir. Toplantı tutanakları "
            + "arşivde beş yıl tutulur.";
    private static final String LEAVE = "Çalışanlara yılda on dört iş günü ücretli yıllık izin verilir.";
    private static final String SPLIT = "İzin en fazla iki parçaya bölünebilir.";
    private static final String ADVANCE = "Avans tutarı en çok 25.000 TL'dir.";
    private static final List<RetrievedPassage> LONG = List.of(
            new RetrievedPassage(DOC_A, "izin.pdf", 2, SourceUnit.PAGE, String.join(" ", FILLER, LEAVE, SPLIT, TAIL), 0.9),
            new RetrievedPassage(DOC_B, "avans.docx", 4, SourceUnit.SECTION, String.join(" ", FILLER, ADVANCE, TAIL), 0.8));

    private final CitationExtractor extractor = new CitationExtractor(properties(1500));

    private static QaProperties properties(int maxPassageChars) {
        return new QaProperties(5, 0.5, maxPassageChars, 1, Duration.ofSeconds(5), 2, Duration.ofSeconds(15),
                Duration.ofSeconds(90), Duration.ofSeconds(30));
    }

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
    void extract_whenTheAnswerHasTwoCitations_excerptsTheSentenceEachOneSupports() {
        Extracted result = extractor.extract("Yıllık izin on dört iş günüdür [1]. Avans en çok 25.000 TL olabilir [2].",
                LONG);
        assertThat(result.citations()).extracting(Citation::number, Citation::excerpt)
                .containsExactly(tuple(1, LEAVE), tuple(2, ADVANCE));
        assertThat(result.answer()).isEqualTo("Yıllık izin on dört iş günüdür [1]. Avans en çok 25.000 TL olabilir [2].");
    }

    @Test
    void extract_whenAMarkerOpensTheNextSentence_usesTheSentenceBeforeIt() {
        Extracted result = extractor.extract("Yıllık izin on dört iş günüdür. [1] Avans 25.000 TL'dir. [2]", LONG);
        assertThat(result.citations()).extracting(Citation::excerpt).containsExactly(LEAVE, ADVANCE);
    }

    @Test
    void extract_whenListItemsCiteDifferentPassages_keepsEachLineToItsOwnMarker() {
        Extracted result = extractor.extract("- Yıllık izin on dört iş günü [1]\n- Avans 25.000 TL [2]", LONG);
        assertThat(result.citations()).extracting(Citation::excerpt).containsExactly(LEAVE, ADVANCE);
    }

    @Test
    void extract_whenOnePassageIsCitedByTwoSentences_excerptsTheWindowSupportingBoth() {
        Extracted result = extractor.extract("Yıllık izin on dört iş günüdür [1]. İki parçaya bölünebilir [1].", LONG);
        assertThat(result.citations()).singleElement().extracting(Citation::excerpt).isEqualTo(LEAVE + " " + SPLIT);
    }

    @Test
    void extract_whenTheAnswerSharesNoWordWithTheLongPassage_citesItsBoundedStart() {
        List<RetrievedPassage> passages = List.of(new RetrievedPassage(DOC_A, "a.pdf", 1, SourceUnit.PAGE,
                "Yıllık\n\tizin " + "kural ".repeat(200), 0.9));
        String excerpt = extractor.extract("Evet [1].", passages).citations().getFirst().excerpt();
        assertThat(excerpt).startsWith("Yıllık izin kural").endsWith("kural…");
        assertThat(excerpt.codePointCount(0, excerpt.length())).isLessThanOrEqualTo(MAX);
    }

    @Test
    void extract_whenTheSupportingSentenceIsBeyondThePromptLimit_fallsBackToThePassageStart() {
        // The prompt carried only the first 100 characters (FILLER): the model cannot have relied on LEAVE.
        CitationExtractor bounded = new CitationExtractor(properties(100));
        String excerpt = bounded.extract("Yıllık izin on dört iş günüdür [1].", LONG).citations().getFirst().excerpt();
        assertThat(excerpt).startsWith("Bu yönetmelik").endsWith("…")
                .isEqualTo(ExcerptSelector.select(LONG.getFirst().content(), ""));
    }

    @Test
    void extract_whenTheAnswerWouldMatchTextOutsideThePassage_excerptStaysPassageText() {
        // llm-rules 3.3: the answer only places the window; its own words never reach the excerpt.
        String excerpt = extractor.extract("Avans 25.000 TL'dir, uydurma ek cümle burada [2].", LONG).citations()
                .getFirst().excerpt();
        assertThat(excerpt).isEqualTo(ADVANCE).doesNotContain("uydurma");
    }
}
