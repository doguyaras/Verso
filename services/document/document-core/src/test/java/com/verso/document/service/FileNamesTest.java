package com.verso.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A file name is a label only: no path, no control or invisible characters, bounded length (reference 9). */
class FileNamesTest {

    @Test
    void sanitize_whenNameCarriesAPath_keepsOnlyTheLastSegment() {
        assertThat(FileNames.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileNames.sanitize("C:\\Users\\x\\rapor.pdf")).isEqualTo("rapor.pdf");
    }

    @Test
    void sanitize_whenNameHasControlOrFormatCharacters_removesThem() {
        // U+202E (right-to-left override) can disguise an extension; U+0000 cannot be stored at all.
        assertThat(FileNames.sanitize("fat\u202Efdp.exe")).isEqualTo("fatfdp.exe");
        assertThat(FileNames.sanitize("a\u0000b\nc.pdf")).isEqualTo("abc.pdf");
    }

    @Test
    void sanitize_whenNameIsMissingOrEmptyAfterCleaning_usesTheFallback() {
        assertThat(FileNames.sanitize(null)).isEqualTo(FileNames.FALLBACK);
        assertThat(FileNames.sanitize("  ")).isEqualTo(FileNames.FALLBACK);
        assertThat(FileNames.sanitize("dir/")).isEqualTo(FileNames.FALLBACK);
        assertThat(FileNames.sanitize("..")).isEqualTo(FileNames.FALLBACK);
    }

    @Test
    void sanitize_whenNameIsLongOrTurkish_keepsLettersAndCapsCodePoints() {
        assertThat(FileNames.sanitize("Şirket İçi Yönetmelik.pdf")).isEqualTo("Şirket İçi Yönetmelik.pdf");
        String longName = "ğ".repeat(400) + ".pdf";
        assertThat(FileNames.sanitize(longName).codePointCount(0, FileNames.sanitize(longName).length()))
                .isEqualTo(FileNames.MAX_CODE_POINTS);
    }
}
