package com.verso.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.testing.TestDocx;
import com.verso.document.testing.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADR-0016: the upload decides the parser from the first bytes and the name, never from the client's media type. */
class DocumentFormatsTest {

    @Test
    void detect_whenThePdfHeaderIsThere_isPdfWhateverTheName() {
        assertThat(DocumentFormats.detect("rapor.docx", TestPdfs.pages("x"))).contains(DocumentFormat.PDF);
        assertThat(DocumentFormats.detect(null, TestPdfs.pages("x"))).contains(DocumentFormat.PDF);
    }

    @Test
    void detect_whenAZipIsNamedDocx_isDocx() {
        assertThat(DocumentFormats.detect("Sözleşme.DOCX", TestDocx.docx(TestDocx.p("x")))).contains(DocumentFormat.DOCX);
        assertThat(DocumentFormats.detect("arşiv.zip", TestDocx.docx(TestDocx.p("x")))).isEmpty();
        assertThat(DocumentFormats.detect("eski.docx", "not a zip".getBytes(StandardCharsets.UTF_8))).isEmpty();
        // An encrypted or legacy Word file is an OLE container, not a ZIP.
        assertThat(DocumentFormats.detect("gizli.docx", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0})).isEmpty();
    }

    @Test
    void detect_whenTextIsNamedTxtOrMd_isTextUnlessItHasNulBytes() {
        byte[] text = "Yıllık izin".getBytes(StandardCharsets.UTF_8);
        assertThat(DocumentFormats.detect("not.txt", text)).contains(DocumentFormat.TXT);
        assertThat(DocumentFormats.detect("not.md", text)).contains(DocumentFormat.MD);
        assertThat(DocumentFormats.detect("not.markdown", text)).contains(DocumentFormat.MD);
        assertThat(DocumentFormats.detect("program.txt", new byte[] {'M', 'Z', 0, 0})).isEmpty();
        byte[] utf16 = {(byte) 0xFF, (byte) 0xFE, 'a', 0};
        assertThat(DocumentFormats.detect("utf16.txt", utf16)).contains(DocumentFormat.TXT);
    }

    @Test
    void detect_whenTheNameOrBytesAreAnythingElse_isNothing() {
        byte[] text = "metin".getBytes(StandardCharsets.UTF_8);
        for (String name : new String[] {"a.exe", "a.html", "a.pdf", "a", null, "a.txt.exe"}) {
            assertThat(DocumentFormats.detect(name, text)).as(String.valueOf(name)).isEqualTo(Optional.empty());
        }
    }
}
