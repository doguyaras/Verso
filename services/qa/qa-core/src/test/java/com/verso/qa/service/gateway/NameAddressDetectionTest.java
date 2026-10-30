package com.verso.qa.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.verso.qa.service.gateway.TurkishPii.Type;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0065: the rules of name and address detection one by one; the labelled set ({@link NameAddressEvalTest}) measures
 * them together. All names and addresses are synthetic.
 */
class NameAddressDetectionTest {

    private static List<String> found(String text, Type type) {
        return TurkishPii.find(text).stream().filter(m -> m.type() == type)
                .map(m -> text.substring(m.start(), m.end())).toList();
    }

    private static List<String> keys(String text, Type type) {
        return TurkishPii.find(text).stream().filter(m -> m.type() == type).map(TurkishPii.Match::key).toList();
    }

    @Test
    void wordLists_areLoaded_andKeepAmbiguousNamesApart() {
        assertThat(TurkishLexicon.FIRST).hasSizeGreaterThan(350);
        assertThat(TurkishLexicon.FIRST_AMBIGUOUS).hasSizeGreaterThan(100).contains("deniz", "umut", "metin", "emin");
        assertThat(TurkishLexicon.SURNAME).hasSizeGreaterThan(250).contains("yılmaz", "öztürk");
        assertThat(TurkishLexicon.PROVINCE).hasSize(81).contains("ankara", "ığdır", "istanbul");
        assertThat(TurkishLexicon.plainFirstName("deniz")).isFalse();
        assertThat(TurkishLexicon.plainFirstName("ayşe")).isTrue();
        assertThat(TurkishLexicon.surname("karaosmanoğlu")).as("patronymic ending").isTrue();
    }

    @Test
    void name_everyInflectedOrCasedForm_hasOneKey_andTheSuffixStaysOutside() {
        String text = "Ayşe Yılmaz, Ayşe Yılmaz'ın, AYŞE YILMAZ'a ve Ayşe Yılmaz’dan";
        assertThat(found(text, Type.AD)).containsExactly("Ayşe Yılmaz", "Ayşe Yılmaz", "AYŞE YILMAZ", "Ayşe Yılmaz");
        assertThat(keys(text, Type.AD)).containsOnly("ayşe yılmaz");
    }

    @Test
    void name_honorificsAndTitles_stayOutsideTheMatch() {
        assertThat(found("Sn. Mehmet Öztürk'e iletildi", Type.AD)).containsExactly("Mehmet Öztürk");
        assertThat(found("Av. Zeynep Kaya ve Prof. Dr. Ali Rıza Tekin", Type.AD))
                .containsExactly("Zeynep Kaya", "Ali Rıza Tekin");
        assertThat(found("Dr. Kaya raporu imzaladı", Type.AD)).containsExactly("Kaya");
        assertThat(found("Ayşe Hanım ve Yılmaz Bey'e", Type.AD)).containsExactly("Ayşe", "Yılmaz");
        assertThat(found("Müdür Bey onayladı", Type.AD)).as("a title is not a name").isEmpty();
        assertThat(found("Genel Müdür Yardımcısı onay verdi", Type.AD)).isEmpty();
    }

    @Test
    void name_ambiguousGivenNames_needASurnameOrATitle() {
        assertThat(found("Deniz Yılmaz izinde", Type.AD)).containsExactly("Deniz Yılmaz");
        assertThat(found("Deniz Hanım izinde", Type.AD)).containsExactly("Deniz");
        assertThat(found("Deniz Kuvvetleri ihalesi", Type.AD)).isEmpty();
        assertThat(found("Bahar Dönemi takvimi", Type.AD)).isEmpty();
        assertThat(found("Metin alanı zorunludur. Emin değilseniz sorun.", Type.AD)).isEmpty();
        assertThat(found("Umut verici sonuçlar", Type.AD)).isEmpty();
    }

    @Test
    void name_insideAnInstitutionALawOrAFixedPhrase_isNotMasked() {
        for (String text : List.of("Hacettepe Üniversitesi ile protokol", "Kadir Has Üniversitesi fuarı",
                "Ahmet Yılmaz İnşaat Ltd. Şti. ile sözleşme", "Umut Kaplan Lojistik firması",
                "Dr. Sadi Konuk Eğitim ve Araştırma Hastanesi", "4857 sayılı İş Kanunu madde 53",
                "Mustafa Kemal Atatürk anma töreni", "Fatih Sultan Mehmet Köprüsü", "Sosyal Güvenlik Kurumu",
                "Koç Holding ve Sabancı Holding")) {
            assertThat(found(text, Type.AD)).as(text).isEmpty();
        }
    }

    @Test
    void name_lowerCase_onlyAsAListedGivenNameAndSurname() {
        assertThat(found("ayşe yılmaz için izin hakkı", Type.AD)).containsExactly("ayşe yılmaz");
        assertThat(found("deniz kenarındaki tesis, kaya gibi sağlam", Type.AD)).isEmpty();
        assertThat(found("ayşe ile görüştüm", Type.AD)).as("a lower-case given name alone is too weak").isEmpty();
    }

    @Test
    void name_writtenWithInvisibleOrNoBreakCharacters_isStillFound() {
        assertThat(keys("Ay​şe Yılmaz onayladı", Type.AD)).containsExactly("ayşe yılmaz");
    }

    @Test
    void address_chainsOfParts_untilTheProvince_suffixOutside() {
        assertThat(found("Adres: Kızılay Mah. Atatürk Bulvarı No:45 D:7 Çankaya/Ankara'dır.", Type.ADRES))
                .containsExactly("Kızılay Mah. Atatürk Bulvarı No:45 D:7 Çankaya/Ankara");
        assertThat(found("Tebligat Bağdat Cad. No:112 Kat:3 Kadıköy/İstanbul adresine", Type.ADRES))
                .containsExactly("Bağdat Cad. No:112 Kat:3 Kadıköy/İstanbul");
        assertThat(found("Esentepe Mah. Büyükdere Cad. No:199 Şişli/İstanbul 34394", Type.ADRES))
                .containsExactly("Esentepe Mah. Büyükdere Cad. No:199 Şişli/İstanbul 34394");
        assertThat(found("Cumhuriyet Mahallesi 1453. Sokak No:8 Daire 4\nBornova/İzmir", Type.ADRES))
                .as("over a line break").containsExactly("Cumhuriyet Mahallesi 1453. Sokak No:8 Daire 4\nBornova/İzmir");
        assertThat(found("No:12 D:5 Çankaya/Ankara", Type.ADRES)).containsExactly("No:12 D:5 Çankaya/Ankara");
        assertThat(found("kargo atatürk cad. no:5 çankaya/ankara adresine", Type.ADRES))
                .as("lower case").containsExactly("atatürk cad. no:5 çankaya/ankara");
    }

    @Test
    void address_sameAddressWrittenTwoWays_hasOneKey() {
        assertThat(keys("Atatürk Cad. No:12, Çankaya/Ankara ve ATATÜRK CAD. NO: 12 ÇANKAYA / ANKARA", Type.ADRES))
                .containsExactly("atatürk cad no 12 çankaya ankara", "atatürk cad no 12 çankaya ankara");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Atatürk Caddesi'nde yol çalışması var.", "Ankara ofisi Cuma kapalı.",
            "Sipariş No: 4821 hazırlanıyor.", "Kızılay Mah. sakinleri bilgilendirildi.", "Madde No:5 ve Kat 3 toplantısı",
            "Web sitesi güncellendi.", "İstanbul/Ankara uçuşları iptal.", "Çankaya/Ankara bölgesinde kesinti var."})
    void address_aPlaceOrANumberAlone_isNotAnAddress(String text) {
        assertThat(found(text, Type.ADRES)).isEmpty();
    }

    @Test
    void longAdversarialInput_staysLinear() {
        String text = ("Ayşe Yılmaz Kaya Demir Mah. No:1 ".repeat(2000)) + "A".repeat(20_000) + " 1".repeat(5000)
                + " tr" + " 1 2 3".repeat(3000) + " No:1 K:2".repeat(5000) + " Sn.".repeat(5000)
                + " Ayşe".repeat(5000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> TurkishPii.find(text));
    }
}
