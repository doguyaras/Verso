package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.service.RefusalDetector.Segment;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-0064: the model's own refusal, in the Turkish forms gemma4:e2b and larger models write. */
class RefusalDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Belgelerde bu sorunun cevabı bulunamadı.",
            "Belgelerde Hacettepe yaz okulunda bir kredilik dersin ücreti hakkında bilgi bulunmamaktadır.",
            "Belgelerde başvuru ücretleri hakkında bilgi bulunmamaktadır [5].",
            "Belgelerde bu konuda bilgi yer almamaktadır.",
            "Pasajlarda müdürün adı yer almıyor.",
            "Verilen belgelerde bu bilgi belirtilmemiştir.",
            "Belgelerde bu bilgiye rastlanmamıştır.",
            "Bu konuda bilgi yok.",
            "Belgelerde bu bilgi mevcut değildir.",
            "Metinde sigorta şirketinin adına dair bir ayrıntı bulunmuyor.",
            "BELGELERDE BU BİLGİ BULUNMAMAKTADIR.",
    })
    void aRefusalInTheModelsOwnWordsIsARefusal(String sentence) {
        assertThat(RefusalDetector.isRefusal(sentence)).as(sentence).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // Substantive answers: there is no limit, the rule names no information and no document.
            "Yönetmelikte yaş sınırı bulunmamaktadır [1].",
            "Lisansüstü programlara başvuruda üst yaş sınırı yoktur [2].",
            // The negated form inside a clause, not closing it.
            "Mazeret belgesi bulunamadığında öğrenci sınava alınmaz [1].",
            "Yönetmelikte belirtilmemiş durumlarda Senato kararları uygulanır [3].",
            "Muhatap adreste bulunamadığında tebligat kapıya yapıştırılır [5].",
            "Hüküm bulunmayan hallerde ilgili mevzuat uygulanır [1].",
            "Yıllık izin yirmi iş günüdür [1].",
    })
    void anAnswerThatOnlySoundsNegativeIsNotARefusal(String sentence) {
        assertThat(RefusalDetector.isRefusal(sentence)).as(sentence).isFalse();
    }

    @Test
    void aRefusalWithACitationIsStillOneRefusalSegment() {
        List<Segment> segments = RefusalDetector.segments(
                "Belgelerde Hacettepe hazırlık muafiyeti için TOEFL iBT'den kaç puan eşdeğer kabul edildiğine dair "
                        + "bilgi bulunmamaktadır. [4]");

        assertThat(segments).extracting(Segment::refusal).containsExactly(true);
    }

    @Test
    void aRefusalFollowedByFillerIsSplitIntoTheRefusalAndTheRest() {
        List<Segment> segments = RefusalDetector.segments("Belgelerde Hacettepe Bilgisayar Mühendisliği için asgari "
                + "ALES puanı hakkında bilgi bulunmamaktadır. Ancak, adayların ilgili mevzuatta belirtilen standart "
                + "puandan az olmamak üzere belirlenen ALES puanına sahip olmaları gerekir [3].");

        assertThat(segments).extracting(Segment::refusal).containsExactly(true, false);
        assertThat(segments.get(1).text()).startsWith("Ancak, adayların").doesNotContain("[3]");
    }

    @Test
    void aRefusalClauseInsideOneSentenceIsSplitAtItsPredicate() {
        List<Segment> segments = RefusalDetector.segments(
                "Belgelerde bu bilgi bulunmamaktadır, ancak müdür Rektör tarafından görevlendirilir [1].");

        assertThat(segments).extracting(Segment::refusal).containsExactly(true, false);
        assertThat(segments.get(1).text()).startsWith("müdür Rektör tarafından görevlendirilir").doesNotContain("[1]");
    }

    /** A fact stated before the negated clause of the same sentence is content; the clause after it is the refusal. */
    @Test
    void aStatedFactBeforeANegatedClauseStaysContent() {
        for (String sentence : List.of(
                "Belgelerde belirtildiği üzere, yıllık izin 14 gündür ve bu sürenin uzatılabileceğine dair bir hüküm "
                        + "bulunmamaktadır [1].",
                "Belgelere göre yıllık izin 14 gündür; ek izin için bir düzenleme bulunmamaktadır [1].")) {
            List<Segment> segments = RefusalDetector.segments(sentence);

            assertThat(segments).as(sentence).extracting(Segment::refusal).containsExactly(false, true);
            assertThat(segments.getFirst().text()).contains("yıllık izin 14 gündür").doesNotContain("[1]");
            assertThat(RefusalDetector.isRefusal(sentence)).as(sentence).isFalse();
        }
    }

    /** Without a fact before the boundary the sentence is wholly a refusal: a document word is not an answer. */
    @Test
    void aRefusalWithOnlyFramingBeforeItsCommaIsWhollyARefusal() {
        for (String sentence : List.of("Belgelerde, bu konuda bilgi bulunmamaktadır.",
                "Belgelerde belirtildiği üzere, bu konuda bilgi bulunmamaktadır [2].",
                "Müdür Rektör tarafından atanır ve belgelerde adı hakkında bilgi bulunmamaktadır [1].",
                // A number before the comma, but no statement: the question's own figure repeated.
                "Belgelerde 9 saat çalışan işçi için, öğle arası hakkında bilgi bulunmamaktadır [1].")) {
            assertThat(RefusalDetector.segments(sentence)).as(sentence).extracting(Segment::refusal)
                    .containsExactly(true);
            assertThat(RefusalDetector.isRefusal(sentence)).as(sentence).isTrue();
        }
    }

    @Test
    void theModelsCopiedFencesAreNotWords() {
        List<Segment> segments = RefusalDetector.segments("Belgelerde kurulda kaç üye bulunduğu bilgisi "
                + "bulunmamaktadır. [[BELGE 1]], [[BELGE 2]] ve [[BELGE 3]] belgelerinde bu bilgiye rastlanmamıştır.");

        assertThat(segments).extracting(Segment::refusal).containsExactly(true, true);
    }

    @Test
    void aPartialAnswerKeepsItsAnswerBeforeTheRefusal() {
        List<Segment> segments = RefusalDetector.segments(
                "Parolalar en az 12 karakter olmalıdır [1].\n\nHesap kilit süresi için belgelerde bilgi bulunamadı.");

        assertThat(segments).extracting(Segment::refusal).containsExactly(false, true);
    }
}
