package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-0019: Turkish sentence boundaries for the excerpt window and for the answer sentence carrying a marker. */
class SentenceSplitterTest {

    private static List<String> sentences(String text) {
        return sentences(text, false);
    }

    private static List<String> sentences(String text, boolean lineBreaks) {
        return SentenceSplitter.split(text, lineBreaks).stream().map(s -> text.substring(s.start(), s.end())).toList();
    }

    @Test
    void split_whenSentencesEndWithAPeriod_splitsBeforeTheNextCapital() {
        assertThat(sentences("Yıllık izin on dört gündür. İzin bölünebilir. Çalışan önceden bildirir."))
                .containsExactly("Yıllık izin on dört gündür.", "İzin bölünebilir.", "Çalışan önceden bildirir.");
    }

    @Test
    void split_whenTheNextWordIsLowerCase_doesNotSplit() {
        // "Gen." is not a known abbreviation; the lower-case word after it still keeps the sentence whole.
        assertThat(sentences("Gen. sekreter karar verdi. Sonra toplantı bitti."))
                .containsExactly("Gen. sekreter karar verdi.", "Sonra toplantı bitti.");
    }

    @Test
    void split_whenNumbersHaveDots_keepsThemInsideTheSentence() {
        assertThat(sentences("Avans 25.000 TL'dir. Prim oranı 3.2 puandır. Ücret 1.250,50 TL olarak ödenir."))
                .containsExactly("Avans 25.000 TL'dir.", "Prim oranı 3.2 puandır.", "Ücret 1.250,50 TL olarak ödenir.");
    }

    @Test
    void split_whenASentenceEndsWithALongNumber_splits() {
        assertThat(sentences("Toplam tutar 25.000. Ödeme ay sonunda yapılır."))
                .containsExactly("Toplam tutar 25.000.", "Ödeme ay sonunda yapılır.");
    }

    @Test
    void split_whenAPeriodClosesAnOrdinal_doesNotSplit() {
        assertThat(sentences("Kural değişti. 2. Dünya Savaşı sonrası 3. Madde yürürlüğe girdi."))
                .containsExactly("Kural değişti.", "2. Dünya Savaşı sonrası 3. Madde yürürlüğe girdi.");
    }

    @Test
    void split_whenAPeriodClosesAnAbbreviation_doesNotSplit() {
        assertThat(sentences("Dr. Ayşe Kaya onayladı. Bkz. Madde 4 ve Prof. Demir'in notu. Örn. İzin formu."))
                .containsExactly("Dr. Ayşe Kaya onayladı.", "Bkz. Madde 4 ve Prof. Demir'in notu.", "Örn. İzin formu.");
    }

    @Test
    void split_whenAbbreviationsAreDottedOrInitials_doesNotSplit() {
        assertThat(sentences("Verso A.Ş. Genel Müdürü M. Kaya imzaladı. T.C. Kimlik No. 12 ile başvurulur."))
                .containsExactly("Verso A.Ş. Genel Müdürü M. Kaya imzaladı.", "T.C. Kimlik No. 12 ile başvurulur.");
    }

    @Test
    void split_whenARomanNumeralEndsWithADot_doesNotSplit() {
        // Turkish lower-casing maps "I" to "ı", so Roman numerals need their own rule.
        assertThat(sentences("Bölüm IV. Kapsam aşağıdadır.")).containsExactly("Bölüm IV. Kapsam aşağıdadır.");
    }

    @Test
    void split_whenOtherTerminatorsAndQuotes_keepsTheClosingQuote() {
        assertThat(sentences("Ne dedi? \"Evet!\" Sonra gitti… Bekledi... Bitti"))
                .containsExactly("Ne dedi?", "\"Evet!\"", "Sonra gitti…", "Bekledi...", "Bitti");
    }

    @Test
    void split_whenTheTextHasNoTerminator_isOneSentence() {
        assertThat(sentences("MADDE 12 YILLIK İZİN çalışanlara verilir")).containsExactly("MADDE 12 YILLIK İZİN çalışanlara verilir");
    }

    @Test
    void split_whenThereIsExtraWhiteSpace_spansExcludeIt() {
        assertThat(sentences("   Birinci cümle.   İkinci cümle.  \n ")).containsExactly("Birinci cümle.", "İkinci cümle.");
        assertThat(SentenceSplitter.split("")).isEmpty();
        assertThat(SentenceSplitter.split(" \n\t ")).isEmpty();
    }

    @Test
    void split_whenLineBreaksAreRequested_splitsListItemsWithoutPeriods() {
        String answer = "- Yıllık izin 14 gündür [1]\n- Avans 25.000 TL'dir [2]";
        assertThat(sentences(answer, true)).containsExactly("- Yıllık izin 14 gündür [1]", "- Avans 25.000 TL'dir [2]");
        // Passages: text extraction breaks lines inside sentences, so line breaks are only white space there.
        assertThat(sentences(answer, false)).hasSize(1);
    }

    @Test
    void split_whenAMarkerPrecedesThePeriod_splitsAfterIt() {
        assertThat(sentences("İzin on dört gündür [1]. Avans 25.000 TL'dir [2].", true))
                .containsExactly("İzin on dört gündür [1].", "Avans 25.000 TL'dir [2].");
    }
}
