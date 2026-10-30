package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.service.KeyFacts.Fact;
import com.verso.qa.service.KeyFacts.Kind;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The key facts of a question (ADR-0060): what is read, how Turkish notation is normalised, and when a card's question
 * is compatible with a new one (the new question's facts are a subset of the card's; negation agrees both ways).
 */
class KeyFactsTest {

    @Test
    void numbers_inTurkishNotation_andWithScaleWords_areOneValue() {
        assertThat(KeyFacts.parse("500.000")).isEqualByComparingTo("500000");
        assertThat(KeyFacts.parse("1,5")).isEqualByComparingTo("1.5");
        assertThat(KeyFacts.parse("1.500,75")).isEqualByComparingTo("1500.75");
        assertThat(KeyFacts.parse("1.5")).isEqualByComparingTo(BigDecimal.valueOf(1.5));
        assertThat(KeyFacts.parse("1.2.3")).isNull();

        assertThat(facts("500.000 TL üstü alım")).containsExactly(new Fact(Kind.AMOUNT, "500000", "TRY"),
                new Fact(Kind.COMPARATOR, "above"));
        assertThat(facts("500 bin liralık alım")).containsExactly(new Fact(Kind.AMOUNT, "500000", "TRY"));
        assertThat(facts("1,5 milyon TL'den büyük")).containsExactly(new Fact(Kind.AMOUNT, "1500000", "TRY"),
                new Fact(Kind.COMPARATOR, "above"));
        assertThat(facts("₺500 ve 20 USD")).containsExactly(new Fact(Kind.AMOUNT, "500", "TRY"),
                new Fact(Kind.AMOUNT, "20", "USD"));
        assertThat(facts("30TL ve 8GB")).containsExactly(new Fact(Kind.AMOUNT, "30", "TRY"),
                new Fact(Kind.QUANTITY, "8", "GB"));
    }

    @Test
    void percentages_quantities_andNumberWords() {
        assertThat(facts("KDV %18 mi?")).containsExactly(new Fact(Kind.PERCENT, "18"));
        assertThat(facts("yüzde 18'lik oran")).containsExactly(new Fact(Kind.PERCENT, "18"));
        assertThat(facts("30 gün içinde")).containsExactly(new Fact(Kind.QUANTITY, "30", "gün"));
        assertThat(facts("otuz günde")).containsExactly(new Fact(Kind.QUANTITY, "30", "gün"));
        assertThat(facts("10 iş günü")).containsExactly(new Fact(Kind.QUANTITY, "10", "iş günü"));
        assertThat(facts("on beş yıl")).containsExactly(new Fact(Kind.QUANTITY, "15", "yıl"));
        assertThat(facts("iki buçuk saat")).containsExactly(new Fact(Kind.QUANTITY, "2.5", "saat"));
        assertThat(facts("beş yüz kişi")).containsExactly(new Fact(Kind.QUANTITY, "500", "kişi"));
        assertThat(facts("üç teklif")).containsExactly(new Fact(Kind.NUMBER, "3"));
    }

    @Test
    void ordinaryWordsThatAreAlsoNumberWords_areNotNumbers() {
        // "bir" is an article, "on" folds from "ön", "altında" is below, "harici" is external, "aralık" a range.
        assertThat(facts("Bir çalışan ön onay almadan altında kalan harici diski aralık bırakmadan kullanabilir mi?"))
                .isEmpty();
        assertThat(facts("ucu açık bir sözleşme")).isEmpty();
        // A lone "bir" is the article even before a unit: "bir günlük", "biri yılda".
        assertThat(facts("bir günlük limit, biri yılda")).isEmpty();
        assertThat(facts("bir milyon TL")).containsExactly(new Fact(Kind.AMOUNT, "1000000", "TRY"));
        assertThat(facts("bir buçuk saat")).containsExactly(new Fact(Kind.QUANTITY, "1.5", "saat"));
        assertThat(facts("on gün")).containsExactly(new Fact(Kind.QUANTITY, "10", "gün"));
    }

    @Test
    void datesMonthsYearsAndTimes() {
        assertThat(facts("01.03.2025 tarihinde")).contains(new Fact(Kind.DATE, "ymd2025-03-01"),
                new Fact(Kind.DATE, "y2025"), new Fact(Kind.DATE, "m03"));
        assertThat(facts("1 Mart 2025'te")).contains(new Fact(Kind.DATE, "ymd2025-03-01"));
        assertThat(facts("2025 yılında")).containsExactly(new Fact(Kind.DATE, "y2025"));
        // 2^32 + 2000 would wrap to 2000 through intValue(): it is a number, not a year.
        assertThat(facts("4294969296 kayıt")).containsExactly(new Fact(Kind.NUMBER, "4294969296"));
        assertThat(facts("Mart ayında")).containsExactly(new Fact(Kind.DATE, "m03"));
        assertThat(facts("saat 09:00'dan sonra")).containsExactly(new Fact(Kind.DATE, "t09:00"));
    }

    @Test
    void codesReferencesQuotesAndNegation() {
        assertThat(facts("PRC-12 prosedürü")).containsExactly(new Fact(Kind.CODE, "PRC-12"));
        assertThat(facts("P1 kaydı")).containsExactly(new Fact(Kind.CODE, "P1"));
        assertThat(facts("madde 7'ye göre")).containsExactly(new Fact(Kind.CODE, "MADDE 7"));
        assertThat(facts("7. madde")).containsExactly(new Fact(Kind.CODE, "MADDE 7"));
        assertThat(facts("Ek-2'deki form")).containsExactly(new Fact(Kind.CODE, "EK 2"));
        assertThat(facts("7/2 vardiya")).containsExactly(new Fact(Kind.CODE, "7/2"));
        assertThat(facts("\"acil alım\" süreci")).containsExactly(new Fact(Kind.QUOTE, "acil alim"));
        assertThat(facts("stokta olmayan ürün")).containsExactly(new Fact(Kind.NEGATION, ""));
        assertThat(facts("bu masraf değildir")).containsExactly(new Fact(Kind.NEGATION, ""));
        assertThat(facts("KDV hariç tutar")).containsExactly(new Fact(Kind.NEGATION, ""));
    }

    @Test
    void conflict_whenTheQuestionStatesADifferentOrAMissingFact() {
        assertThat(conflict("50.000 TL üstü alımda kaç teklif gerekir?", "500.000 TL üstü alımda kaç teklif gerekir?"))
                .contains(Kind.AMOUNT);
        assertThat(conflict("500 bin TL üstü alım", "500.000 TL üstü alım")).isEmpty();
        assertThat(conflict("P3 olay kaç saatte çözülür?", "P1 olay kaç saatte çözülür?")).contains(Kind.CODE);
        assertThat(conflict("Fatura 30 saatte onaylanır mı?", "Fatura 30 günde onaylanır mı?")).contains(Kind.QUANTITY);
        assertThat(conflict("2024 bütçesi", "2025 bütçesi")).contains(Kind.DATE);
        assertThat(conflict("madde 8 neyi düzenler?", "madde 7 neyi düzenler?")).contains(Kind.CODE);
        assertThat(conflict("KDV %8 mi?", "KDV %18 mi?")).contains(Kind.PERCENT);
        assertThat(conflict("\"acil alım\" kim onaylar?", "Alımı kim onaylar?")).contains(Kind.QUOTE);
    }

    @Test
    void conflict_whenBothCompareInDifferentDirections() {
        // ADR-0060's own motivating pair: one word apart, cosine above the threshold (review finding, 2026-10-30)
        assertThat(conflict("5 yıldan az çalışan için izin kaç gün?", "5 yıldan fazla çalışan için izin kaç gün?"))
                .contains(Kind.COMPARATOR);
        assertThat(conflict("En az kaç gün izin kullanılır?", "En fazla kaç gün izin kullanılır?"))
                .contains(Kind.COMPARATOR);
        assertThat(conflict("500.000 TL'yi aşan alım", "500.000 TL'den düşük alım")).contains(Kind.COMPARATOR);
        assertThat(facts("en çok 3 gün")).contains(new Fact(Kind.COMPARATOR, "max"));
        assertThat(facts("en az 3 gün")).contains(new Fact(Kind.COMPARATOR, "min"));
    }

    @Test
    void noConflict_whenOnlyOneSideCompares_orBothTheSameWay() {
        assertThat(conflict("500.000 TL'yi aşan alım kimde?", "500.000 TL üzeri alım kimde?")).isEmpty();
        assertThat(conflict("500.000 TL'lik alımı kim onaylar?", "500.000 TL üstü alımı kim onaylar?")).isEmpty();
        assertThat(conflict("5 yıldan fazla çalışanın izni", "5 yıldan fazla hizmeti olanın izni")).isEmpty();
    }

    @Test
    void conflict_negationMustAgreeBothWays() {
        assertThat(conflict("Stokta olmayan ürün iade edilir mi?", "Stoktaki ürün iade edilir mi?"))
                .contains(Kind.NEGATION);
        assertThat(conflict("Stoktaki ürün iade edilir mi?", "Stokta olmayan ürün iade edilir mi?"))
                .contains(Kind.NEGATION);
    }

    @Test
    void noConflict_whenTheCardIsAsSpecificOrMore_orWritesTheSameFactDifferently() {
        assertThat(conflict("Beş yıldan az çalışan kaç gün izin alır?", "5 yıldan az hizmeti olanın izni kaç gün?"))
                .isEmpty();
        assertThat(conflict("Üç teklif hangi tutardan itibaren gerekir?", "3 ayrı teklif kaç TL'den başlar?"))
                .isEmpty();
        assertThat(conflict("Alım onayı kimde?", "500.000 TL üstü alım onayı kimde?")).as("card more specific").isEmpty();
        assertThat(conflict("acil alım kim onaylar?", "\"Acil alım\" kim onaylar?")).isEmpty();
        assertThat(conflict("\"acil alım\" kim onaylar?", "Acil alımı kim onaylar? (acil alım süreci)")).isEmpty();
        assertThat(conflict("prc-12 neyi kapsar?", "PRC-12 neyi kapsar?")).isEmpty();
        assertThat(conflict("30 gün kuralı nedir?", "30 kuralı nedir?")).as("bare number on the card").isEmpty();
    }

    @Test
    void blankText_hasNoFacts_andToStringCarriesNoText() {
        assertThat(KeyFacts.of(null).facts()).isEmpty();
        assertThat(KeyFacts.of("  ").facts()).isEmpty();
        assertThat(KeyFacts.of("500.000 TL QZXMARKER").toString()).doesNotContain("500").doesNotContain("QZX");
    }

    private static java.util.Set<Fact> facts(String text) {
        return KeyFacts.of(text).facts();
    }

    private static Optional<Kind> conflict(String question, String card) {
        return KeyFacts.of(question).conflictWith(KeyFacts.of(card));
    }
}
