package com.verso.connector.confluence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Storage format to Markdown (ADR-0044): headings stay headings (the chunker splits on them), tables become GitHub
 * tables, macros are kept or dropped by a closed list, entities are decoded and invisible characters removed.
 */
class StorageFormatConverterTest {

    @Test
    void toMarkdown_whenHeadingsAndParagraphs_keepsTheHeadingLevels() {
        String md = StorageFormatConverter.toMarkdown("İzin politikası",
                "<h1>Genel</h1><p>Yıllık izin <strong>14 gün</strong>dür.</p><h2>İstisnalar</h2><p>Bir <em>not</em>.</p>"
                        + "<h3>Ek</h3><p>Son</p>");

        assertThat(md).isEqualTo("# İzin politikası\n\n# Genel\n\nYıllık izin **14 gün**dür.\n\n## İstisnalar\n\n"
                + "Bir *not*.\n\n### Ek\n\nSon");
    }

    @Test
    void toMarkdown_whenATableHasHeaderAndPipes_writesAGithubTable() {
        String md = StorageFormatConverter.toMarkdown("T",
                "<table><tbody><tr><th>Kod</th><th>Anlam</th></tr><tr><td><p>A|B</p></td><td>Birinci</td></tr>"
                        + "<tr><td>C</td><td><ul><li>iç</li></ul></td></tr></tbody></table>");

        assertThat(md).isEqualTo("# T\n\n| Kod | Anlam |\n| --- | --- |\n| A\\|B | Birinci |\n| C | iç |");
    }

    @Test
    void toMarkdown_whenEntitiesAndInvisibleCharacters_decodesAndRemovesThem() {
        String md = StorageFormatConverter.toMarkdown("E",
                "<p>Fiyat&nbsp;&gt;&nbsp;10 &amp; &rsquo;tırnak&rsquo; &#252;</p><p>gizli​‮metin⁦</p>");

        assertThat(md).isEqualTo("# E\n\nFiyat > 10 & ’tırnak’ ü\n\ngizlimetin");
    }

    @Test
    void toMarkdown_whenMacros_keepsPanelsAndCodeAndDropsTheRest() {
        String md = StorageFormatConverter.toMarkdown("M",
                "<ac:structured-macro ac:name=\"info\"><ac:parameter ac:name=\"title\">GIZLI-PARAM</ac:parameter>"
                        + "<ac:rich-text-body><p>Bilgi kutusu</p></ac:rich-text-body></ac:structured-macro>"
                        + "<ac:structured-macro ac:name=\"code\"><ac:parameter ac:name=\"language\">java</ac:parameter>"
                        + "<ac:plain-text-body><![CDATA[if (a < b && c) { x = \"```\"; }]]></ac:plain-text-body>"
                        + "</ac:structured-macro>"
                        + "<ac:structured-macro ac:name=\"toc\"><ac:parameter ac:name=\"maxLevel\">3</ac:parameter>"
                        + "</ac:structured-macro>"
                        + "<ac:structured-macro ac:name=\"html\"><ac:plain-text-body><![CDATA[<script>alert(1)</script>"
                        + "]]></ac:plain-text-body></ac:structured-macro>"
                        + "<p>Bkz. <ac:link><ri:page ri:content-title=\"Diğer sayfa\"/></ac:link> ve "
                        + "<ac:image><ri:attachment ri:filename=\"x.png\"/></ac:image>resim.</p>"
                        + "<script>evil()</script><style>p{}</style>");

        assertThat(md).isEqualTo("# M\n\nBilgi kutusu\n\n````\nif (a < b && c) { x = \"```\"; }\n````\n\n"
                + "Bkz. Diğer sayfa ve resim.");
        assertThat(md).doesNotContain("GIZLI-PARAM", "maxLevel", "alert", "evil", "java");
    }

    @Test
    void toMarkdown_whenListsAndTasks_writesMarkdownLists() {
        String md = StorageFormatConverter.toMarkdown("L",
                "<ol><li>bir<ul><li>alt</li></ul></li><li>iki</li></ol>"
                        + "<ac:task-list><ac:task><ac:task-status>complete</ac:task-status><ac:task-body>bitti"
                        + "</ac:task-body></ac:task><ac:task><ac:task-status>incomplete</ac:task-status>"
                        + "<ac:task-body>açık</ac:task-body></ac:task></ac:task-list>");

        assertThat(md).isEqualTo("# L\n\n1. bir\n   - alt\n2. iki\n\n- [x] bitti\n- [ ] açık");
    }

    @Test
    void toMarkdown_whenTextStartsWithAHashOrNestsVeryDeep_staysTextAndTerminates() {
        String deep = "<div>".repeat(5_000) + "derin" + "</div>".repeat(5_000);

        assertThat(StorageFormatConverter.toMarkdown("", "<p># başlık değil</p>")).isEqualTo("\\# başlık değil");
        assertThat(StorageFormatConverter.toMarkdown("D", deep)).isEqualTo("# D\n\nderin");
    }
}
