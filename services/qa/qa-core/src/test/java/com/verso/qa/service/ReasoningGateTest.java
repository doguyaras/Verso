package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.dto.SystemEntityDetail;
import com.verso.document.api.enums.SystemEntityType;
import com.verso.qa.service.ReasoningGate.Reason;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** The cheap gate of multi-step reasoning (ADR-0055): Turkish comparison phrases, multi-part questions, entities. */
class ReasoningGateTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "P1 olayında BT ile müşteri destek SLA'ları arasındaki fark nedir?",
            "BT ve müşteri destek ilk yanıt sürelerinin farkı ne kadar?",
            "İzin ve masraf onay adımlarını karşılaştır lütfen",
            "Depo ve satın alma onaylarını karşılaştırır mısın?",
            "Hem BT hem müşteri destek için P1 süresi kaç saat?",
            "ERP'deki onay eşiği ile WMS'teki sevkiyat onayı nasıl ilişkili?",
            "Masraf politikası ve satın alma prosedürü arasında onay limiti nasıl?",
            "KARŞILAŞTIRMA: iki belgedeki parola kuralları nedir?",
            "İki prosedürü kıyasla: hangisi daha uzun sürer?"})
    void lexical_whenTheQuestionCompares_isComparison(String question) {
        assertThat(ReasoningGate.lexical(question)).isEqualTo(Reason.COMPARISON);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Yıllık izin kaç gün ve izni kim onaylar?",
            "Doğum izni kaç hafta, talep ne zaman yapılmalı?",
            "VPN oturumu kaç saat sürer? Uzaktan çalışma kaç gündür?"})
    void lexical_whenTheQuestionAsksTwoThings_isMultiPart(String question) {
        assertThat(ReasoningGate.lexical(question)).isEqualTo(Reason.MULTI_PART);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Beş yıldan az hizmeti olan çalışan kaç gün izin kullanır?",
            "Fark nedir?",
            "P1 ve P2 arasında?",
            "Depo ile ofis",
            "WMS-204 hata kodu ne anlama gelir?",
            "Kullanılmayan yıllık izin bir sonraki yıla devredilebilir mi?"})
    void lexical_whenTheQuestionIsOneThingOrShort_isNone(String question) {
        assertThat(ReasoningGate.lexical(question)).isEqualTo(Reason.NONE);
    }

    @Test
    void decide_whenTheSystemModelFindsTwoSystems_isEntities() {
        String question = "ERP onay adımı WMS sevkiyat onayını etkiler mi?";
        assertThat(ReasoningGate.decide(question, List.of(entity(SystemEntityType.SYSTEM, "ERP"),
                entity(SystemEntityType.SYSTEM, "WMS")))).isEqualTo(Reason.ENTITIES);
        // A system and one of its codes are two things to look up.
        assertThat(ReasoningGate.decide(question, List.of(entity(SystemEntityType.SYSTEM, "WMS"),
                entity(SystemEntityType.CODE, "WMS-204")))).isEqualTo(Reason.ENTITIES);
    }

    @Test
    void decide_whenOnlyOneDistinctSystemOrOnlyTerms_isNone() {
        String question = "ERP onay adımı WMS sevkiyat onayını etkiler mi?";
        assertThat(ReasoningGate.decide(question, List.of(entity(SystemEntityType.SYSTEM, "ERP"),
                entity(SystemEntityType.SYSTEM, "ERP")))).isEqualTo(Reason.NONE);
        assertThat(ReasoningGate.decide(question, List.of(entity(SystemEntityType.TERM, "FEFO"),
                entity(SystemEntityType.TERM, "SKT")))).isEqualTo(Reason.NONE);
        assertThat(ReasoningGate.decide(question, List.of())).isEqualTo(Reason.NONE);
        assertThat(ReasoningGate.decide(question, null)).isEqualTo(Reason.NONE);
        // A short question is never split, whatever it names.
        assertThat(ReasoningGate.decide("ERP WMS?", List.of(entity(SystemEntityType.SYSTEM, "ERP"),
                entity(SystemEntityType.SYSTEM, "WMS")))).isEqualTo(Reason.NONE);
    }

    @Test
    void worthLookingUp_whenTheLexicalPartDecidedOrTheQuestionIsShort_isFalse() {
        assertThat(ReasoningGate.worthLookingUp("BT ile destek arasındaki fark nedir?")).isFalse();
        assertThat(ReasoningGate.worthLookingUp("WMS nedir?")).isFalse();
        assertThat(ReasoningGate.worthLookingUp("ERP onay adımı WMS sevkiyat onayını etkiler mi?")).isTrue();
    }

    private static SystemEntityDetail entity(SystemEntityType type, String key) {
        return new SystemEntityDetail(type, key, key, List.of(), List.of(), List.of(), false);
    }
}
