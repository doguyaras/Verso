package com.verso.qa.service;

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
        assertThat(result.citations()).containsExactly(new Citation(2, DOC_B, "b.docx", 7, CitationUnit.SECTION),
                new Citation(1, DOC_A, "a.pdf", 3, CitationUnit.PAGE));
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
}
