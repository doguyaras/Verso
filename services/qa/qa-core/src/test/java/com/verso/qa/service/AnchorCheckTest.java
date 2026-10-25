package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnchorCheckTest {

    private static final String LEAVE = """
            Hizmet süresi bir yıldan beş yıla kadar olan çalışanlara yılda on dört iş günü yıllık izin verilir.
            Doğum yapan çalışana toplam on altı hafta ücretli doğum izni verilir.
            Evlenen çalışana üç iş günü, eşi doğum yapan çalışana beş iş günü babalık izni verilir.""";
    private static final String CODES = """
            Kod | Anlamı | Ne yapılır
            WMS-204 | Yetersiz kullanılabilir stok | Stok durumunu sorgulayın.
            WMS-502 | ERP bağlantısı yok | İşleme devam edin; terminal kayıtları 60 dakikaya kadar yerelde tutar.""";

    private static AnchorCheck.Result check(String sentence, String passage) {
        return AnchorCheck.check(sentence, List.of(passage), "");
    }

    @Test
    void numberWordsAndDigitsAreTheSameQuantity() {
        assertThat(check("Yılda 14 iş günü yıllık izin kullanılır.", LEAVE).unverified()).isFalse();
        assertThat(check("Doğum izni toplam on altı haftadır.", LEAVE).unverified()).isFalse();
        assertThat(check("Doğum izni toplam 16 haftadır.", LEAVE).unverified()).isFalse();
    }

    @Test
    void aChangedQuantityIsUnverified() {
        AnchorCheck.Result result = check("Doğum izni toplam on dört haftadır.", LEAVE);
        assertThat(result.unverified()).isTrue();
        assertThat(result.missing()).containsExactly("N:14");
        assertThat(check("Doğum izni toplam 20 haftadır.", LEAVE).missing()).containsExactly("N:20");
    }

    @Test
    void aQuantityTakenFromAnotherRuleOfThePassageIsUnverified() {
        // "üç" is in the passage, but for marriage: paternity leave is "beş iş günü".
        assertThat(check("Babalık izni üç gündür.", LEAVE).unverified()).isTrue();
        // A known limit (ADR-0047): with a shared neighbour ("çalışana") the wrong rule's number passes.
        assertThat(check("Eşi doğum yapan çalışana üç hafta babalık izni verilir.", LEAVE).unverified()).isFalse();
        // The same number with the same words around it is supported, wherever it stands.
        assertThat(check("Evlenen bir çalışan üç iş günü izin alır.", LEAVE).unverified()).isFalse();
    }

    @Test
    void separatorsAndSuffixesDoNotChangeANumber() {
        String passage = "5.000 TL ile 25.000 TL arasındaki masraflar Finans Müdürü onayına gider.";
        assertThat(check("25000 TL'ye kadar olan masraflar Finans Müdürü onayına gider.", passage).unverified()).isFalse();
        assertThat(check("Kargo kesim saati 16.30'dur.", "Kargo için günlük kesim saati 16.30'dur.").unverified())
                .isFalse();
        assertThat(check("Ödeme on beşinci iş gününde yapılır.", "Ödeme ayın 15. iş gününde yapılır.").unverified())
                .isFalse();
        assertThat(check("Bilgi dördüncü maddede.", "Ayrıntı dört numaralı maddede.").unverified()).isFalse();
    }

    @Test
    void ordinaryWordsThatLookLikeNumbersAreNotAnchors() {
        // "bir" is an article, "altında" means below, "yüzde" is percent, "onu" is a pronoun.
        AnchorCheck.Result result = check("Bir kayıt altında tutulur; onu yüzde olarak ölçeriz.", "Kayıt tutulur.");
        assertThat(result.checked()).isZero();
        assertThat(result.unverified()).isFalse();
    }

    @Test
    void aCompoundOfAmbiguousStemsIsANumber() {
        assertThat(check("Süre yirmi bir gündür.", "Süre yirmi bir gündür.").checked()).isEqualTo(1);
        assertThat(check("Süre yirmi bir gündür.", "Süre yirmi iki gündür.").unverified()).isTrue();
        assertThat(check("Tutar bin beş yüz liradır.", "Tutar 1.500 liradır.").unverified()).isFalse();
    }

    @Test
    void codesMustBeTheCodeOfTheirRow() {
        assertThat(check("WMS-502 durumunda terminal kayıtları yerelde tutar.", CODES).unverified()).isFalse();
        // The code is in the passage, but in another row.
        assertThat(check("WMS-502 hatası yetersiz kullanılabilir stok demektir.", CODES).unverified()).isTrue();
        assertThat(check("SAT-E058 hatası yetersiz stok demektir.", CODES).missing()).containsExactly("C:SAT-E058");
        assertThat(check("Kayıt P1 olarak açılır.", "Yazıcı ağda görünmüyorsa kayıt P3 açılır.").unverified()).isTrue();
    }

    @Test
    void aNumberTheAskerWroteMayBeRepeated() {
        String passage = "25.000 TL'yi aşan masraflar için Genel Müdür onayı gerekir.";
        String sentence = "30.000 TL'lik bir masraf için Genel Müdür onayı gerekir.";
        assertThat(AnchorCheck.check(sentence, List.of(passage), "30.000 TL'lik masrafı kim onaylar?").unverified())
                .isFalse();
        assertThat(AnchorCheck.check(sentence, List.of(passage), "Masrafı kim onaylar?").unverified()).isTrue();
    }

    @Test
    void anyCitedPassageMaySupportTheSentence() {
        assertThat(AnchorCheck.check("Doğum izni on altı haftadır.", List.of(CODES, LEAVE), null).unverified()).isFalse();
        assertThat(AnchorCheck.check("Doğum izni on altı haftadır.", List.of(), null).unverified()).isTrue();
    }

    @Test
    void negationAndNamesAreOutOfReach() {
        // By design (ADR-0047): only quantities and codes are checked.
        assertThat(check("Doğum izni ücretsizdir.", LEAVE).checked()).isZero();
        assertThat(check("Doğum yapan çalışana toplam on altı hafta ücretsiz izin verilmez.", LEAVE).unverified())
                .isFalse();
    }

    @Test
    void aPassageInAnotherLanguageIsComparedByItsQuantitiesOnly() {
        String english = "Annual leave is twenty working days; five days carry over to the next year.";
        assertThat(check("Yıllık izin yirmi iş günüdür.", english).unverified()).isFalse();
        assertThat(check("Beş gün devreder.", english).unverified()).isFalse();
        assertThat(check("Yıllık izin on dört iş günüdür.", english).missing()).containsExactly("N:14");
        assertThat(check("Yıllık izin 120 gündür.", "Annual leave is one hundred twenty days.").unverified()).isFalse();
    }

    @Test
    void englishNumberWordsInTheSentenceAreNotAnchors() {
        // The sentence side reads Turkish only: "one" in an answer is not a claim the check could verify.
        assertThat(check("Use one badge.", "Badges are personal.").checked()).isZero();
    }

    @Test
    void fullWidthDigitsAndDottedCapitalsAreFolded() {
        assertThat(check("Yılda １４ iş günü izin verilir.", LEAVE).unverified()).isFalse();
        assertThat(check("YILDA ON DÖRT İŞ GÜNÜ İZİN VERİLİR.", LEAVE).unverified()).isFalse();
    }
}
