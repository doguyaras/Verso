package com.verso.platform.core.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class TurkishTextTest {

    @Test
    void fold_whenTurkishLettersAndCases_readsAlike() {
        assertThat(TurkishText.fold("İADE")).isEqualTo("iade");
        assertThat(TurkishText.fold("IŞIK")).isEqualTo("isik");
        assertThat(TurkishText.fold("Dördüncü")).isEqualTo("dorduncu");
        assertThat(TurkishText.foldUpper("İK-3")).isEqualTo("IK-3");
        assertThat(TurkishText.foldUpper("sat-ü1")).isEqualTo("SAT-U1");
    }

    @Test
    void codeRegex_whenTokensMixed_matchesCodesOnly() {
        Pattern code = Pattern.compile(TurkishText.CODE_REGEX);
        assertThat(code.matcher("Hata WMS-204 oluştu").find()).isTrue();
        assertThat(code.matcher("P1 kaydı").find()).isTrue();
        assertThat(code.matcher("WMS sistemi").find()).isFalse();
        assertThat(code.matcher("2024 yılı").find()).isFalse();
    }
}
