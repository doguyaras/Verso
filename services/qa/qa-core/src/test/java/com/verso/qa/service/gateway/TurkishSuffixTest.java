package com.verso.qa.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ADR-0065: a suffix the model wrote after a placeholder is fitted to the restored value; a correct one stays. */
class TurkishSuffixTest {

    @ParameterizedTest(name = "{0} + ''{1} = ''{2}")
    @CsvSource({
            // vowel harmony, four-way and two-way
            "Ayşe Yılmaz, in, ın", "Ayşe Yılmaz, ın, ın", "Ayşe Yılmaz, e, a", "Mehmet Öztürk, un, ün",
            "Mehmet Öztürk, a, e", "Ahmet Kaya, nin, nın", "Hatice Koç, in, un", "Zeynep Kaya, den, dan",
            // buffer consonants after a vowel, dropped after a consonant
            "Ayşe, ın, nin", "Ayşe, a, ye", "Ayşe, ı, yi", "Ahmet Kaya, e, ya", "Ahmet Kaya, ın, nın",
            "Yılmaz, nin, ın", "Yılmaz, ya, a", "Demir, yle, le", "Öztürk, yı, ü",
            // d/t and c/ç after a voiceless consonant
            "Ahmet, de, te", "Ahmet, ta, te", "Ayşe, te, de", "Yılmaz, ta, da", "Ahmet, ydi, ti", "Mehmet, ce, çe",
            // -ki keeps its i; loanword names with front suffixes
            "Çankaya/Ankara, deki, daki", "Konak/İzmir, daki, deki", "Ankara, dakiler, dakiler", "Kemal, ın, in",
            "Hilal, a, e",
            // upper case suffix after an upper case value
            "AYŞE YILMAZ, İN, IN"})
    void fit(String value, String suffix, String expected) {
        assertThat(TurkishSuffix.fit(value, suffix)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} + ''{1} stays")
    @CsvSource({"No:12 D:5 06690, da", "THY, de", "Ayşe Yılmaz, In", "Ayşe Yılmaz, ''"})
    void unjudgeable_staysAsWritten(String value, String suffix) {
        assertThat(TurkishSuffix.fit(value, suffix)).isEqualTo(suffix);
    }
}
