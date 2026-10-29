package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.ChunkContext.Located;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ADR-0061: the context header per chunk; index input only, bounded, deterministic. */
class ChunkContextTest {

    private static final String TITLE = "Hacettepe Üniversitesi Biyomühendislik Araştırma Merkezi (HÜBAUM) Yönetmeliği";

    private static final String PAGE_1 = """
            HACETTEPE ÜNİVERSİTESİ BİYOMÜHENDİSLİK
            ARAŞTIRMA MERKEZİ (HÜBAUM) YÖNETMELİĞİ
            BİRİNCİ BÖLÜM
            Amaç, Kapsam, Dayanak ve Tanımlar
            Amaç
            MADDE 1 – (1) Bu Yönetmeliğin amacı, Merkezin amaçlarına ilişkin esasları düzenlemektir.
            Müdür
            MADDE 7 – (1) Müdür, Rektör tarafından üç yıl için görevlendirilir.""";
    private static final String PAGE_2 = """
            (2) Müdür, görevi başında olmadığı zaman yardımcılarından birini vekil bırakır.
            Yönetim kurulu
            MADDE 8 – (1) Yönetim Kurulu beş üyeden oluşur.""";

    @Test
    void headers_namesTheArticleWithItsTitleLine() {
        List<String> headers = ChunkContext.headers(TITLE, List.of(PAGE_1, PAGE_2), List.of(
                new Located(1, "Müdür\nMADDE 7 – (1) Müdür, Rektör tarafından üç yıl için görevlendirilir."),
                new Located(2, "(2) Müdür, görevi başında olmadığı zaman yardımcılarından birini vekil bırakır."),
                new Located(2, "Yönetim kurulu\nMADDE 8 – (1) Yönetim Kurulu beş üyeden oluşur.")));

        assertThat(headers.get(0)).isEqualTo(TITLE + "\nMadde 7 – Müdür");
        assertThat(headers.get(1)).as("an article runs over the page break").isEqualTo(TITLE + "\nMadde 7 – Müdür");
        assertThat(headers.get(2)).isEqualTo(TITLE + "\nMadde 8 – Yönetim kurulu");
    }

    @Test
    void headers_whenTheChunkOpensBeforeTheArticle_takesTheHeadingInForceAThirdIn() {
        String chunk = PAGE_1.substring(PAGE_1.indexOf("BİRİNCİ BÖLÜM"));

        String header = ChunkContext.headers(TITLE, List.of(PAGE_1), List.of(new Located(1, chunk))).getFirst();

        assertThat(header).startsWith(TITLE + "\n").doesNotContain("HACETTEPE");
    }

    @Test
    void headers_withoutAnyHeading_isTheTitleAlone() {
        List<String> headers = ChunkContext.headers("Depo Kılavuzu", List.of("sayım her ay yapılır."),
                List.of(new Located(1, "sayım her ay yapılır.")));

        assertThat(headers).containsExactly("Depo Kılavuzu");
    }

    @Test
    void headers_readsMarkdownAndNumberedHeadings() {
        String page = "## Kullanıcı Yönetimi\nHesaplar BT tarafından açılır.\n3.2 Parola Kuralları\nParola 12 karakterdir.";

        List<String> headers = ChunkContext.headers("BT Politikası", List.of(page), List.of(
                new Located(1, "Hesaplar BT tarafından açılır."), new Located(1, "Parola 12 karakterdir.")));

        assertThat(headers).containsExactly("BT Politikası\nKullanıcı Yönetimi", "BT Politikası\n3.2 Parola Kuralları");
    }

    @Test
    void headings_doNotTakeASentenceOrATableRowForAHeading() {
        String page = "Madde 5'e göre başvuru yapılır.\n| AD | SOYAD |\n|---|---|\nGENEL HÜKÜMLER BUNLARDIR.";

        assertThat(ChunkContext.headings(page)).isEmpty();
    }

    @Test
    void header_isBoundedAndCutsTheSectionFirst() {
        String title = "Başlık ".repeat(25).strip();
        String section = "Bölüm adı ".repeat(20).strip();

        String header = ChunkContext.header(title, section);

        assertThat(header.length()).isLessThanOrEqualTo(ChunkContext.MAX_HEADER_CHARS);
        assertThat(header).startsWith(title).doesNotContain(ChunkContext.SEPARATOR);
        assertThat(ChunkContext.header("x".repeat(400), null)).hasSize(ChunkContext.MAX_HEADER_CHARS);
    }

    @Test
    void header_dropsASectionThatIsPartOfTheTitle() {
        assertThat(ChunkContext.header(TITLE, "HACETTEPE ÜNİVERSİTESİ BİYOMÜHENDİSLİK")).isEqualTo(TITLE);
    }

    @Test
    void embeddingInput_isHeaderBlankLineText_andTheTextStaysUnchanged() {
        assertThat(ChunkContext.embeddingInput("T\nS", "metin", true)).isEqualTo("T\nS\n\nmetin");
        assertThat(ChunkContext.embeddingInput("", "metin", true)).isEqualTo("metin");
        assertThat(ChunkContext.embeddingInput("T\nS", "metin", false)).as("embed-header=false").isEqualTo("metin");
    }

    /** Each mode has its own stored version, so switching embed-header re-indexes; both differ from NULL (legacy). */
    @Test
    void indexVersion_differsPerMode() {
        assertThat(ChunkContext.indexVersion(true)).isNotEqualTo(ChunkContext.indexVersion(false));
        assertThat(ChunkContext.indexVersion(false)).isPositive();
    }

    /** The real chunker's output is located in its page, table parts and overlaps included. */
    @Test
    void headers_overTheChunkersOwnOutput_givesEveryChunkAHeader() {
        String page = PAGE_1 + "\n" + "Uzun bir paragraf cümlesi. ".repeat(60);
        PageChunker chunker = new PageChunker(TestPdfs.properties(500, 2_000_000, 300, 50));
        List<PageChunker.Chunk> chunks = chunker.chunk(List.of(page, PAGE_2));

        List<String> headers = ChunkContext.headers(TITLE, List.of(page, PAGE_2),
                chunks.stream().map(c -> new Located(c.pageNumber(), c.content())).toList());

        assertThat(headers).hasSameSizeAs(chunks)
                .allSatisfy(h -> assertThat(h).startsWith(TITLE).hasSizeLessThanOrEqualTo(ChunkContext.MAX_HEADER_CHARS));
        // Page 2 is one chunk; its first third is still article 7 (carried over the page break).
        assertThat(headers.getLast()).endsWith("Madde 7 – Müdür");
    }
}
