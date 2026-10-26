package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.service.AnchorCheck.Difference;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0052: the contradiction scan compares numbers and codes of two near-duplicate chunks with ADR-0047's folding.
 * Only values next to the same describing words count; the same value written two ways is no contradiction.
 */
class ContradictionCheckTest {

    @Test
    void differences_whenTheNumbersOfTheSameRuleDiffer_reportsBothValues() {
        List<Difference> found = AnchorCheck.differences("Yıllık izin süresi 14 iş günüdür.",
                "Yıllık izin süresi 16 iş günüdür.", 5);
        assertThat(found).containsExactly(new Difference("14", "16"));
    }

    @Test
    void differences_whenTheSameNumberIsWrittenAsWordsOrWithoutDiacritics_findsNone() {
        assertThat(AnchorCheck.differences("Yıllık izin süresi on dört iş günüdür.",
                "Yillik izin suresi 14 is gunudur.", 5)).isEmpty();
        assertThat(AnchorCheck.differences("Masraf limiti 25.000 TL'dir.", "Masraf limiti 25000 TL'dir.", 5)).isEmpty();
    }

    @Test
    void differences_whenCodesOfTheSameRowDiffer_reportsTheCodes() {
        List<Difference> found = AnchorCheck.differences("Kritik arıza kaydı SAT-E058 kodu ile açılır.",
                "Kritik arıza kaydı SAT-E059 kodu ile açılır.", 5);
        assertThat(found).containsExactly(new Difference("SAT-E058", "SAT-E059"));
        // The same code with a folded letter (Ü/U) is one code.
        assertThat(AnchorCheck.differences("Kritik arıza kaydı SAT-Ü1 kodu ile açılır.",
                "Kritik arıza kaydı SAT-U1 kodu ile açılır.", 5)).isEmpty();
    }

    @Test
    void differences_whenNumbersStandNextToDifferentWords_findsNone() {
        // Different things: a probation period and a notice period are not a contradiction.
        assertThat(AnchorCheck.differences("Deneme süresi 2 aydır.", "Ihbar önellerinde bildirim 4 haftadır.", 5))
                .isEmpty();
        // A number and a code never pair up.
        assertThat(AnchorCheck.differences("Yıllık izin kodu 14 olarak girilir.", "Yıllık izin kodu IZ-14 olarak girilir.",
                5)).isEmpty();
    }

    @Test
    void differences_areBoundedByTheLimit() {
        String a = "Yıllık izin süresi 14 gün. Yıllık izin süresi 15 gün. Yıllık izin süresi 17 gün.";
        String b = "Yıllık izin süresi 20 gün. Yıllık izin süresi 21 gün. Yıllık izin süresi 22 gün.";
        assertThat(AnchorCheck.differences(a, b, 2)).hasSize(2);
    }
}
