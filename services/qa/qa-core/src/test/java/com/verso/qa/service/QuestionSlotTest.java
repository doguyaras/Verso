package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.service.QuestionSlot.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ADR-0064: what a Turkish question asks for, and whether a text gives it. */
class QuestionSlotTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Bağımlılık Merkezinin şu anki müdürü kimdir?|PERSON",
            "Bu belgeyi kime teslim etmeliyim?|PERSON",
            "Harcama talimatını kimler imzalar?|PERSON",
            "Kimin onayı gerekir?|PERSON",
            "Kimlik kartı kaybolursa ne yapılır?|OTHER",
            "Kimya laboratuvarında hangi önlemler alınır?|ENTITY",
            "Tebligat ne zaman yapılmış sayılır?|DATE",
            "Rapor hangi gün gönderilir?|DATE",
            "Kütüphane kaçta kapanır?|DATE",
            "Merkez nerede bulunur?|PLACE",
            "HUZEM hangi kampüste ve hangi binada bulunuyor?|PLACE",
            "Gündeliklerin miktarı nerede belirlenir?|PLACE",
            "Yönetim kurulu kaç kişiden oluşur?|NUMBER",
            "Fazla çalışma ücreti ne kadar artırılır?|NUMBER",
            "Devamsızlık sınırı yüzde kaçtır?|NUMBER",
            "Personelin özel sağlık sigortası hangi şirketten alınıyor?|ENTITY",
            "Yıllık izin nasıl kullanılır?|OTHER",
            "Ara sınav nedir?|OTHER",
            // Question words are whole words: "kaçağı", "kaçış", "ayrıntılar", "ayarlar" and "yıllık" ask nothing.
            "Gaz kaçağı durumunda ne yapılır?|OTHER",
            "Acil kaçış planı nedir?|OTHER",
            "Kaçınılmaz durumlarda ne yapılır?|OTHER",
            "Sözleşmede hangi ayrıntılar yer alır?|ENTITY",
            "Hangi ayarlar değiştirilmeli?|ENTITY",
            "Hangi yıllık izin türleri vardır?|ENTITY",
            "Hangi aylarda sayım yapılır?|DATE",
            "Kaçıncı maddede düzenlenir?|NUMBER",
    })
    void theQuestionWordGivesTheType(String question, Type type) {
        assertThat(QuestionSlot.typeOf(question)).as(question).isEqualTo(type);
    }

    @Test
    void aQuestionThatAsksSeveralThingsIsMultiPart() {
        assertThat(QuestionSlot.multiPart("Parolalar en az kaç karakter olmalı ve hesabı kim açar?")).isTrue();
        assertThat(QuestionSlot.multiPart("P1 çözüm hedefini müşteri kayıtlarıyla karşılaştır.")).isTrue();
        assertThat(QuestionSlot.multiPart("İki kayıt arasındaki fark nedir?")).isTrue();
        // One kind asked twice, or a quantity asked twice, is still one thing to give.
        assertThat(QuestionSlot.multiPart("HUZEM hangi kampüste ve hangi binada bulunuyor?")).isFalse();
        assertThat(QuestionSlot.multiPart("Kaç gün önce başvurulur ve izin ne kadar sürer?")).isFalse();
        assertThat(QuestionSlot.multiPart("Bağımlılık Merkezinin şu anki müdürü kimdir?")).isFalse();
    }

    @Test
    void aCurrentOfficeHolderNeedsAName() {
        String question = "Bağımlılık Uygulama ve Araştırma Merkezinin şu anki müdürü kimdir?";
        assertThat(QuestionSlot.filledBy(Type.PERSON, question, "Müdür, Merkezin faaliyet alanları ile ilgili "
                + "çalışmaları bulunan Üniversite öğretim üyeleri arasından Rektör tarafından üç yıl süreyle "
                + "görevlendirilir.")).isFalse();
        assertThat(QuestionSlot.filledBy(Type.PERSON, "Hacettepe Üniversitesinin şu anki rektörü kimdir?",
                "Rektör, Hacettepe Üniversitesi Rektörü'nü ifade eder.")).isFalse();
        assertThat(QuestionSlot.filledBy(Type.PERSON, question, "Merkez müdürü Prof. Dr. Ayşe Demir'dir.")).isTrue();
        assertThat(QuestionSlot.filledBy(Type.PERSON, question, "Merkezin müdürü Mehmet Kaya'dır.")).isTrue();
        // A short name inside a longer question word ("Ali" in "mali") is still a name.
        assertThat(QuestionSlot.filledBy(Type.PERSON, "Şu anki mali işler müdürü kimdir?",
                "Mali İşler Müdürü Ali Kaya'dır.")).isTrue();
        // "şu anlaşma" is not "şu an": the signer's role answers it.
        assertThat(QuestionSlot.filledBy(Type.PERSON, "Şu anlaşmayı kim imzaladı?",
                "Anlaşma Genel Müdür tarafından imzalanır.")).isTrue();
    }

    @Test
    void anyOtherWhoQuestionIsAnsweredByARole() {
        String question = "Harcama talimatını kim onaylar?";
        assertThat(QuestionSlot.filledBy(Type.PERSON, question, "Harcama talimatını harcama yetkilisi onaylar.")).isTrue();
        assertThat(QuestionSlot.filledBy(Type.PERSON, question, "Talimat Dekan tarafından onaylanır.")).isTrue();
        assertThat(QuestionSlot.filledBy(Type.PERSON, question, "talimat üç gün içinde onaylanır.")).isFalse();
    }

    @Test
    void aQuantityQuestionNeedsANumberInTheAskedUnit() {
        String coins = "Bilgi edinme başvurusunda on sayfayı aşan her sayfa için kaç kuruş ücret alınır?";
        assertThat(QuestionSlot.filledBy(Type.NUMBER, coins, "Bilgi veya belgelerin ilk on sayfasının kopyaları için "
                + "postalama maliyeti dahil herhangi bir ücret alınmaz.")).isFalse();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, coins, "On sayfayı aşan her sayfa için 50 kuruş alınır.")).isTrue();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, coins, "Her sayfa için 0,50 TL alınır.")).isTrue();
        // A length of time in another unit is still a length of time.
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "İtiraz kaç gün içinde yapılır?",
                "İtiraz iki hafta içinde yapılır.")).isTrue();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Kurul kaç kişiden oluşur?",
                "Kurul dört öğretim üyesinden oluşur.")).isTrue();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Kurul kaç kişiden oluşur?",
                "Kurul öğretim üyelerinden oluşur.")).isFalse();
        // "ay" is a word of its own: "ayrıca" is not a month.
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Staj kaç ay sürer?", "Staj ayrıca 3 hafta da sürebilir."))
                .isTrue();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Sözleşme kaç aylık yapılır?", "Ayrıca bir sözleşme yapılır."))
                .isFalse();
        // A lone scale word with the unit is a quantity; without one it stays a word (a face).
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Başvuru ücreti kaç TL?", "Başvuru ücreti bin TL'dir."))
                .isTrue();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Geçme notu kaç puan?", "Geçme notu yüz puan üzerinden altmıştır."))
                .isTrue();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Başvuru ücreti kaç TL?", "Başvuru yüz yüze yapılır.")).isFalse();
    }

    /** Measured on the live stack (external set, first run): right answers the check must keep. */
    @Test
    void compoundNumberWordsAndAnyKindOfMemberAreQuantities() {
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Programın toplam eğitim süresi kaç yarıyıldır?",
                "Tıp-Bilim Doktoru Bütünleşik Programının eğitim süresi oniki yarıyıldır.")).isTrue();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Çalışma süresi haftada en çok kaç saattir?",
                "Genel bakımdan çalışma süresi haftada en çok kırkbeş saattir.")).isTrue();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "HOSMER'in yönetim kurulu toplam kaç kişiden oluşur?",
                "Merkez Yönetim Kurulu, Rektör tarafından seçilen 4 öğretim elemanı ile Merkez Müdürü ve 2 "
                        + "yardımcısından oluşur.")).isTrue();
        // "onay" and "binası" are words, not numbers.
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Kaç gün içinde onaylanır?", "Dekan onayı ile binada yapılır."))
                .isFalse();
    }

    @Test
    void aQuantityQuestionWithoutAUnitTakesAnyQuantityOrShare() {
        String raise = "İşçiye fazla çalışma için ödenecek saat ücreti normal ücrete göre ne kadar artırılır?";
        assertThat(QuestionSlot.filledBy(Type.NUMBER, raise, "Normal ücretin yüzde elli yükseltilmesiyle ödenir."))
                .isTrue();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Ne kadar süre izin verilir?",
                "Fakülte Yönetim Kurulu kararı ile iki yıla kadar izin verilebilir.")).isTrue();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, "Eksikler için ne kadar ek süre verilir?",
                "Eksik kalan işleri tamamlaması için telafi süresi verilir.")).isFalse();
    }

    @Test
    void aTimeOfDayQuestionNeedsAnHour() {
        String question = "Üniversite kütüphanesi sınav haftalarında kaça kadar açık kalıyor?";
        assertThat(QuestionSlot.filledBy(Type.NUMBER, question,
                "Sınavlar en az bir hafta önce ilan edilir.")).isFalse();
        assertThat(QuestionSlot.filledBy(Type.NUMBER, question, "Kütüphane saat 23.00'e kadar açıktır.")).isTrue();
    }

    @Test
    void aNamedKindOfPlaceMustBeNamed() {
        String question = "Uzaktan Eğitim Merkezi (HUZEM) hangi kampüste ve hangi binada bulunuyor?";
        assertThat(QuestionSlot.filledBy(Type.PLACE, question,
                "Merkez (HUZEM), Hacettepe Üniversitesi Uzaktan Eğitim Uygulama ve Araştırma Merkezidir.")).isFalse();
        assertThat(QuestionSlot.filledBy(Type.PLACE, question, "Merkez Beytepe kampüsünde, Kütüphane binasındadır."))
                .isTrue();
        // "nerede belirlenir": the act that sets it is the place.
        assertThat(QuestionSlot.filledBy(Type.PLACE, "Yurtiçi gündeliklerinin miktarı nerede belirlenir?",
                "Yurtiçi gündeliklerinin miktarı her yıl bütçe kanunları ile tespit olunur.")).isTrue();
    }

    @Test
    void aDateQuestionTakesADateOrARelativeTime() {
        String question = "Tebligat ne zaman yapılmış sayılır?";
        assertThat(QuestionSlot.filledBy(Type.DATE, question,
                "Tebligat yapıştırılma tarihinden itibaren onbeş gün sonra yapılmış sayılır.")).isTrue();
        assertThat(QuestionSlot.filledBy(Type.DATE, "Geciken teslimatlar raporu hangi gün e-postayla gelir?",
                "Geciken teslimatlar raporu e-postayla gelir.")).isFalse();
    }

    @Test
    void aNamedCompanyIsAProperName() {
        String question = "Personelin özel sağlık sigortası hangi şirketten alınıyor?";
        assertThat(QuestionSlot.filledBy(Type.ENTITY, question, "Tıbbi kötü uygulamaya ilişkin zorunlu malî "
                + "sorumluluk sigortası yaptırdığı sigorta şirketinden talep edilir.")).isFalse();
        assertThat(QuestionSlot.filledBy(Type.ENTITY, question, "Sigorta, Anadolu Sigorta A.Ş. ile yapılır.")).isTrue();
        // Any other "hangi" question has no slot of its own.
        assertThat(QuestionSlot.filledBy(Type.ENTITY, "Hangi belgeler istenir?", "nüfus cüzdanı ve diploma")).isTrue();
        assertThat(QuestionSlot.checkable(Type.ENTITY, "Hangi belgeler istenir?")).isFalse();
        assertThat(QuestionSlot.checkable(Type.ENTITY, question)).isTrue();
    }

    @Test
    void aPassageStatesAQuantityInTurkishOrEnglish() {
        assertThat(QuestionSlot.anyNumber(List.of("Annual leave is twenty working days."))).isTrue();
        assertThat(QuestionSlot.anyNumber(List.of("Kurul üyeleri Rektör tarafından seçilir.", "Süre 15 gündür.")))
                .isTrue();
        assertThat(QuestionSlot.anyNumber(List.of("Kurul üyeleri Rektör tarafından seçilir."))).isFalse();
    }
}
