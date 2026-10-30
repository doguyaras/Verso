package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.config.QaRelevanceProperties;
import com.verso.qa.service.AnswerRelevance.Result;
import com.verso.qa.service.AnswerRelevance.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-0064: the order of the checks, and the answers measured on the live stack (eval-data/external, p55 run). */
class AnswerRelevanceTest {

    private static final AnswerRelevance CHECK = new AnswerRelevance(new QaRelevanceProperties(true));
    private static final List<String> PASSAGE = List.of("Müdür, Rektör tarafından üç yıl süreyle görevlendirilir.");

    private static Result sourced(String question, String answer) {
        return CHECK.check(question, answer, PASSAGE, true, false);
    }

    // --- The three failures that opened ADR-0064 (regression) ---

    @Test
    void theCurrentDirectorAnsweredWithTheAppointmentRuleIsNotFound() {
        Result result = sourced("Bağımlılık Uygulama ve Araştırma Merkezinin şu anki müdürü kimdir?",
                "Müdür, Merkezin faaliyet alanları ile ilgili çalışmaları bulunan Üniversite öğretim üyeleri arasından "
                        + "Rektör tarafından üç yıl süreyle görevlendirilir [1].");

        assertThat(result.verdict()).isEqualTo(Verdict.SLOT_MISSING);
        assertThat(result.questionType()).isEqualTo("person");
    }

    @Test
    void theInsuranceCompanyAnsweredWithAnUnrelatedClauseIsNotFound() {
        Result result = sourced("Personelin özel sağlık sigortası hangi şirketten alınıyor?",
                "Tıbbi kötü uygulamaya ilişkin zorunlu malî sorumluluk sigortası yaptırdığı sigorta şirketinden talep "
                        + "edilir [1].");

        assertThat(result.verdict()).isEqualTo(Verdict.SLOT_MISSING);
        assertThat(result.questionType()).isEqualTo("entity");
    }

    @Test
    void aRefusalWithACitationIsNotFound() {
        Result result = sourced("Hacettepe hazırlık muafiyeti için TOEFL iBT'den kaç puan eşdeğer kabul ediliyor?",
                "Belgelerde Hacettepe hazırlık muafiyeti için TOEFL iBT'den kaç puan eşdeğer kabul edildiğine dair "
                        + "bilgi bulunmamaktadır. [4]");

        assertThat(result.verdict()).isEqualTo(Verdict.REFUSAL);
        assertThat(result.answered()).isFalse();
    }

    // --- Refusals ---

    @Test
    void anUncitedRefusalInTheModelsOwnWordsIsNotFound() {
        assertThat(CHECK.check("İş sağlığı ve güvenliği kurulu ne sıklıkla toplanır?",
                "Belgelerde iş sağlığı ve güvenliği kurulunun ne sıklıkla toplandığına dair bilgi bulunmamaktadır.",
                List.of(), false, false).verdict()).isEqualTo(Verdict.REFUSAL);
    }

    @Test
    void aRefusalPaddedWithARelatedRuleIsNotFound() {
        Result result = sourced("Hacettepe Bilgisayar Mühendisliği tezli yüksek lisans programı için istenen asgari "
                        + "ALES puanı kaçtır?",
                "Belgelerde Hacettepe Bilgisayar Mühendisliği tezli yüksek lisans programı için istenen asgari ALES "
                        + "puanı hakkında bilgi bulunmamaktadır. Ancak, tezli yüksek lisans programlarına başvuracak "
                        + "adayların ilgili mevzuatta belirtilen standart puandan az olmamak üzere belirlenen ALES "
                        + "puanına sahip olmaları gerekir [3].");

        assertThat(result.verdict()).isEqualTo(Verdict.REFUSAL_WITH_FILLER);
    }

    @Test
    void aRefusalFollowedByTheAskedQuantityIsStillAnAnswer() {
        // Measured (is-06): the model refused too early, then gave the rule with its durations; the fact was right.
        Result result = sourced("Günde 9 saat çalışan bir işçiye öğle arası olarak en az ne kadar dinlenme verilmelidir?",
                "Belgelerde günde 9 saat çalışan bir işçiye öğle arası olarak en az ne kadar dinlenme verilmesi "
                        + "gerektiğine dair doğrudan bir bilgi bulunmamaktadır.\n\nAncak, ara dinlenme süreleri:\n\n"
                        + "*   Yedibuçuk saatten fazla süreli işlerde bir saat ara dinlenmesi verilir [1].");

        assertThat(result.verdict()).isEqualTo(Verdict.ANSWERS);
    }

    @Test
    void aRefusalPaddedWithTheWrongKindOfTimeIsNotFound() {
        assertThat(sourced("Üniversite kütüphanesi sınav haftalarında kaça kadar açık kalıyor?",
                "Belgelerde sınav haftalarının ne kadar açık kaldığına dair bilgi bulunmamaktadır. Ancak, genel "
                        + "sınavlar sınavlardan en az bir hafta önce ilan edilir [1].").verdict())
                .isEqualTo(Verdict.REFUSAL_WITH_FILLER);
    }

    // --- Slots ---

    @Test
    void anAnswerThatGivesTheAskedQuantityIsAnAnswer() {
        assertThat(CHECK.check("Yıllık izin kaç gün?", "Yıllık izin yirmi iş günüdür [1].",
                List.of("Annual leave is twenty working days."), true, false).verdict()).isEqualTo(Verdict.ANSWERS);
    }

    @Test
    void aQuantityThatNoCitedPassageStatesIsNotAnAnswer() {
        assertThat(CHECK.check("Yönetim kurulu kaç kişiden oluşur?", "Kurul beş üyeden oluşur [1].",
                List.of("Kurul üyeleri Rektör tarafından seçilir."), true, false).verdict())
                .isEqualTo(Verdict.SLOT_MISSING);
    }

    @Test
    void aWrongUnitIsNotAnAnswer() {
        assertThat(sourced("Bilgi edinme başvurusunda on sayfayı aşan her sayfa için kaç kuruş ücret alınır?",
                "Bilgi veya belgelerin ilk on sayfasının kopyaları için, postalama maliyeti dahil herhangi bir ücret "
                        + "alınmaz [1].").verdict()).isEqualTo(Verdict.SLOT_MISSING);
    }

    @Test
    void anOpenQuestionHasNoSlotToMiss() {
        assertThat(sourced("Yıllık izin nasıl kullanılır?", "İzin amirin onayıyla kullanılır [1].").verdict())
                .isEqualTo(Verdict.ANSWERS);
    }

    @Test
    void aRefusalFollowedByTheAnswerToAYesNoQuestionIsKept() {
        // Measured (external multi-hop mh-07): the rest gave the right rate.
        assertThat(sourced("Taşınmaz kiralama ihalelerinde alınan kesin teminat oranı Kamu İhale Kanunu'ndaki oranla "
                        + "aynı mı?",
                "Belgelerde taşınmaz kiralama ihalelerine ilişkin kesin teminat oranları hakkında doğrudan bilgi "
                        + "bulunmamaktadır. Ancak, sözleşmeden önce ihale bedelinin yüzde altısı oranında kesin teminat "
                        + "alınır [1].").verdict()).isEqualTo(Verdict.ANSWERS);
        assertThat(sourced("Hediye alma yasağına girer mi?", "Belgelerde bağışlara dair doğrudan bilgi "
                + "bulunmamaktadır. Ancak, kamu görevlisi hediye alamaz [1].").verdict()).isEqualTo(Verdict.ANSWERS);
        // An open question has no slot either, but its padding is not an answer (internal isg-yok-3).
        assertThat(sourced("İş güvenliği uzmanına hangi dahili numaradan ulaşılır?", "Belgelerde iş güvenliği "
                + "uzmanına ulaşılacağı belirtilmemiştir. Cihaz kaybında 4444 numaralı iç hatta bildirim yapılır [1].")
                .verdict()).isEqualTo(Verdict.REFUSAL_WITH_FILLER);
    }

    // --- Where only a refusal is judged ---

    @Test
    void aPartialAnswerToAQuestionAskingSeveralThingsIsKept() {
        assertThat(sourced("Parolalar en az kaç karakter olmalı ve hesabı kim açar?",
                "Belgelerde hesabı kimin açtığına dair bilgi bulunmamaktadır. Parolalar en az 12 karakter "
                        + "olmalıdır [1].").verdict()).isEqualTo(Verdict.ANSWERS);
    }

    @Test
    void aMultiStepSynthesisIsJudgedOnlyAsAWholeRefusal() {
        assertThat(CHECK.check("Bağımlılık Merkezinin şu anki müdürü kimdir?", "Müdür üç yıl için atanır [1].",
                PASSAGE, true, true).verdict()).isEqualTo(Verdict.ANSWERS);
        assertThat(CHECK.check("Bağımlılık Merkezinin şu anki müdürü kimdir?",
                "Belgelerde bu bilgi bulunmamaktadır [1].", PASSAGE, true, true).verdict())
                .isEqualTo(Verdict.REFUSAL);
    }

    @Test
    void anUncitedOrUnverifiedAnswerIsNotSlotChecked() {
        assertThat(CHECK.check("Bağımlılık Merkezinin şu anki müdürü kimdir?", "Müdür üç yıl için atanır.",
                List.of(), false, false).verdict()).isEqualTo(Verdict.ANSWERS);
    }

    @Test
    void offChangesNothing() {
        AnswerRelevance off = new AnswerRelevance(new QaRelevanceProperties(false));

        Result result = off.check("Kim?", "Belgelerde bu bilgi bulunmamaktadır [1].", PASSAGE, true, false);

        assertThat(result.answered()).isTrue();
        assertThat(result.questionType()).isEqualTo("off");
        assertThat(off.enabled()).isFalse();
    }
}
