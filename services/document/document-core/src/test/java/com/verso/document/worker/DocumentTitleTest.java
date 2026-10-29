package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-0061: the title a document's chunks are indexed under. */
class DocumentTitleTest {

    private static final String REGULATION = """
            Resmî Gazete Tarihi: 12.03.2015 Resmî Gazete Sayısı: 29293
            YÖNETMELİK
            Hacettepe Üniversitesinden:
            HACETTEPE ÜNİVERSİTESİ BİYOMÜHENDİSLİK ARAŞTIRMA VE
            UYGULAMA MERKEZİ (HÜBAUM) YÖNETMELİĞİ
            BİRİNCİ BÖLÜM
            Amaç, Kapsam, Dayanak ve Tanımlar
            Amaç
            MADDE 1 – (1) Bu Yönetmeliğin amacı, Hacettepe Üniversitesi Biyomühendislik Araştırma ve Uygulama
            Merkezinin amaçlarına, faaliyet alanlarına ilişkin usul ve esasları düzenlemektir.
            """;

    @Test
    void of_whenTheFirstPageStartsWithACapitalTitleOverTwoLines_joinsThemInTurkishTitleCase() {
        String title = DocumentTitle.of(List.of(REGULATION), "Microsoft Word - 2015.docx", "29293.pdf");

        assertThat(title).isEqualTo(
                "Hacettepe Üniversitesi Biyomühendislik Araştırma ve Uygulama Merkezi (HÜBAUM) Yönetmeliği");
    }

    @Test
    void of_keepsTheParenthesisedAbbreviationAsWritten() {
        String title = DocumentTitle.of(List.of("UZAKTAN EĞİTİM MERKEZİ (HUZEM) YÖNERGESİ\nMADDE 1 – Amaç"), null,
                "a.pdf");

        assertThat(title).contains("(HUZEM)").startsWith("Uzaktan Eğitim Merkezi");
    }

    /** Turkish capitals: dotted İ and dotless I lower to i and ı, not to i̇ (Locale.ROOT would). */
    @Test
    void titleCase_usesTurkishLetters() {
        assertThat(DocumentTitle.titleCase("ISPARTA İLİ BİLİMSEL ARAŞTIRMA İLE İLGİLİ ESASLAR"))
                .isEqualTo("Isparta İli Bilimsel Araştırma ile İlgili Esaslar");
        assertThat(DocumentTitle.titleCase("MERKEZİ'NİN GÖREVLERİ")).isEqualTo("Merkezi'nin Görevleri");
    }

    /** Without any Turkish capital the text is English or ASCII-only Turkish: I stays i, not ı. */
    @Test
    void titleCase_withoutTurkishCapitals_usesTheRootRules() {
        assertThat(DocumentTitle.titleCase("INFORMATION SECURITY POLICY")).isEqualTo("Information Security Policy");
        assertThat(DocumentTitle.titleCase("DEPO YONETIMI (DYS)")).isEqualTo("Depo Yonetimi (DYS)");
    }

    @Test
    void of_whenTheFirstLineIsAMixedCaseHeading_usesIt() {
        String page = "Bilgi Güvenliği Politikası\nBu politika şirketin bilgi varlıklarını korumak için yazılmıştır.";

        assertThat(DocumentTitle.of(List.of(page), null, "politika.pdf")).isEqualTo("Bilgi Güvenliği Politikası");
    }

    @Test
    void of_whenThePageOpensWithBodyText_fallsBackToTheMetadataTitle() {
        String page = "Bu belge, depo işlemlerinin nasıl yapılacağını anlatır. İlk adım sayımdır.";

        assertThat(DocumentTitle.of(List.of(page), "Depo İşlemleri Kılavuzu", "x.pdf"))
                .isEqualTo("Depo İşlemleri Kılavuzu");
    }

    @Test
    void of_whenTheMetadataTitleIsWriterNoise_fallsBackToTheFileName() {
        String page = "bu belge küçük harfle başlar ve bir başlığı yoktur.";

        assertThat(DocumentTitle.of(List.of(page), "Microsoft Word - belge1.docx", "hubaum-yonetmeligi_2015.pdf"))
                .isEqualTo("hubaum yonetmeligi 2015");
        assertThat(DocumentTitle.of(List.of(page), "Untitled", "Depo_Kılavuzu.docx")).isEqualTo("Depo Kılavuzu");
    }

    @Test
    void of_whenNothingIsKnown_isNeverBlank() {
        assertThat(DocumentTitle.of(List.of(), null, null)).isEqualTo("belge");
        assertThat(DocumentTitle.of(List.of(""), null, ".pdf")).isNotBlank();
    }

    @Test
    void of_skipsALoneCapitalWordAndTheGazetteLines() {
        String page = "T.C.\nGİZLİ\nANKARA ÜNİVERSİTESİ\nKALİTE KOMİSYONU YÖNERGESİ\nMADDE 1 – Amaç";

        assertThat(DocumentTitle.of(List.of(page), null, "x.pdf")).isEqualTo("Ankara Üniversitesi Kalite Komisyonu Yönergesi");
    }

    @Test
    void of_stripsAMarkdownHeadingMarker() {
        assertThat(DocumentTitle.of(List.of("## Satın Alma Prosedürü\n\n1. Talep açılır."), null, "x.pdf"))
                .isEqualTo("Satın Alma Prosedürü");
    }

    /** A long name is cut at a word, and an abbreviation the cut removed is appended again (it must reach the index). */
    @Test
    void bound_whenTheTitleIsLong_keepsTheAbbreviation() {
        String longName = "ÇOK UZUN BİR ADI OLAN BİR KURUMUN BAĞLI\n".repeat(3) + "ARAŞTIRMA MERKEZİ (HÜBAUM) YÖNETMELİĞİ";

        String title = DocumentTitle.of(List.of(longName + "\nMADDE 1 – Amaç"), null, "x.pdf");

        assertThat(title.length()).isLessThanOrEqualTo(DocumentTitle.MAX_TITLE_CHARS);
        assertThat(title).endsWith("(HÜBAUM)");
    }
}
