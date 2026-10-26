package com.verso.document.systemmodel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The privacy rule (ADR-0054): an owner phrase becomes a title, never a person's name. */
class RoleNamesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = ';', nullValues = "-", value = {
            "Depo Sorumlusu Ahmet Yılmaz; Depo Sorumlusu",
            "Ahmet Yılmaz Depo Müdürü; Depo Müdürü",
            "Ayşe Kaya Depo Müdürü; Depo Müdürü",
            "Ahmet Yılmaz (Muhasebe Uzmanı); Muhasebe Uzmanı",
            "Satın Alma Müdürü Zeynep Hanım; Satın Alma Müdürü",
            "Bu süreçten Kalite Departmanı; Kalite Departmanı",
            "Depo Sorumlusu'nun; Depo Sorumlusu",
            "Dr. Elif Şahin; -",
            "Mehmet Bey; -",
            "Ahmet Yılmaz; -",
            "sorumlu; -",
            "Ekip; -"
    })
    void title_whenPhraseHasNamesAndTitles_keepsTheTitleOnly(String phrase, String expected) {
        assertThat(RoleNames.title(phrase).orElse(null)).isEqualTo(expected);
    }
}
