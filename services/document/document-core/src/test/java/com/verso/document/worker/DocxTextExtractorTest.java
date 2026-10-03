package com.verso.document.worker;

import static com.verso.document.testing.TestDocx.docx;
import static com.verso.document.testing.TestDocx.heading;
import static com.verso.document.testing.TestDocx.p;
import static com.verso.document.testing.TestDocx.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.config.DocumentProperties;
import com.verso.document.testing.TestDocx;
import com.verso.document.testing.TestPdfs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/** ADR-0016: Word text with the JDK alone, and every way an untrusted DOCX could cost memory, CPU or a file read. */
class DocxTextExtractorTest {

    private final DocxTextExtractor extractor = new DocxTextExtractor(TestPdfs.properties());

    @Test
    void extract_whenHeadingsStartTopics_cutsOneSectionPerHeadingWithTurkishText() {
        List<String> sections = extractor.extract(docx(
                heading("Balk1", "Yıllık İzin"), p("Çalışan yılda on dört gün izin kullanır."),
                heading("Heading2", "Ücretsiz izin"), p("Şirket onayıyla otuz güne kadar.")));

        assertThat(sections).containsExactly(
                "Yıllık İzin\n\nÇalışan yılda on dört gün izin kullanır.",
                "Ücretsiz izin\n\nŞirket onayıyla otuz güne kadar.");
    }

    @Test
    void extract_whenTablesTabsAndBreaksAppear_keepsRowsTogetherAndSkipsDeletedText() {
        String body = table(new String[] {"Tutar", "Onaylayan"}, new String[] {"25.000 TL", "Genel Müdür"})
                + "<w:p><w:r><w:t>Bir</w:t><w:tab/><w:t>iki</w:t><w:br/><w:t>üç</w:t></w:r>"
                + "<w:del><w:r><w:delText>silinen metin</w:delText></w:r></w:del>"
                + "<w:r><w:instrText>PAGE</w:instrText></w:r></w:p>";

        String text = String.join("\n", extractor.extract(TestDocx.document(body)));

        assertThat(text).contains("Tutar | Onaylayan", "25.000 TL | Genel Müdür", "Bir iki\nüç")
                .doesNotContain("silinen").doesNotContain("PAGE");
    }

    /** A text box holds paragraphs inside a paragraph: their text joins the outer one instead of erasing it. */
    @Test
    void extract_whenATextBoxSitsInAParagraph_keepsBothTexts() {
        String body = "<w:p><w:r><w:t>Önce</w:t></w:r><w:r><w:txbxContent>" + p("kutu") + "</w:txbxContent></w:r>"
                + "<w:r><w:t>sonra</w:t></w:r></w:p>";

        assertThat(extractor.extract(TestDocx.document(body)).getFirst()).contains("Önce", "kutu", "sonra");
    }

    /** Review K2: Word writes a text box twice (choice and fallback); a moved paragraph sits at both ends. Read once. */
    @Test
    void extract_whenTextBoxesHaveAFallbackOrTextWasMoved_readsItOnce() {
        String box = "<w:txbxContent>" + p("KUTU") + "</w:txbxContent>";
        String body = "<w:p xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"><w:r><w:t>Metin</w:t></w:r>"
                + "<w:r><mc:AlternateContent><mc:Choice Requires=\"wps\">" + box + "</mc:Choice>"
                + "<mc:Fallback>" + box + "</mc:Fallback></mc:AlternateContent></w:r></w:p>"
                + "<w:moveFrom><w:r><w:t>TAŞINAN</w:t></w:r></w:moveFrom><w:p><w:moveTo><w:r><w:t>TAŞINAN</w:t></w:r></w:moveTo></w:p>";

        String text = String.join("\n", extractor.extract(TestDocx.document(body)));

        assertThat(text.split("KUTU", -1)).as("the box once").hasSize(2);
        assertThat(text).contains("Metin KUTU").doesNotContain("MetinKUTU");
        assertThat(text.split("TAŞINAN", -1)).as("the moved text once").hasSize(2);
    }

    /** Review B4: what the reader cannot see in Word must not reach the model (a hidden instruction, for example). */
    @Test
    void extract_whenARunIsHidden_skipsItButNotAnExplicitlyVisibleOne() {
        String body = "<w:p><w:r><w:t>Görünen </w:t></w:r>"
                + "<w:r><w:rPr><w:vanish/></w:rPr><w:t>GIZLI talimat</w:t></w:r>"
                + "<w:r><w:rPr><w:webHidden/></w:rPr><w:t>WEB</w:t></w:r>"
                + "<w:r><w:rPr><w:vanish w:val=\"0\"/></w:rPr><w:t>açık</w:t></w:r></w:p>";

        assertThat(extractor.extract(TestDocx.document(body))).containsExactly("Görünen açık");
    }

    /** Review K3: outline level 9 is "body text" in Word; 0-8 are headings. */
    @Test
    void extract_whenAnOutlineLevelIsSet_onlyLevelsBelowNineStartSections() {
        String body = p("giriş") + "<w:p><w:pPr><w:outlineLvl w:val=\"9\"/></w:pPr><w:r><w:t>gövde</w:t></w:r></w:p>"
                + "<w:p><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr><w:r><w:t>Başlık</w:t></w:r></w:p>" + p("içerik");

        assertThat(extractor.extract(TestDocx.document(body))).containsExactly("giriş\n\ngövde", "Başlık\n\niçerik");
    }

    /** Review K6: part names are case-insensitive in Office Open XML. */
    @Test
    void extract_whenTheMainPartNameHasOtherCase_readsIt() {
        assertThat(extractor.extract(TestDocx.zip(Map.of("Word/Document.xml", TestDocx.documentXml(p("büyük harf"))))))
                .containsExactly("büyük harf");
    }

    /** Review B2: the element depth limit is set explicitly, not left to the JDK's configuration. */
    @Test
    void extract_whenElementsNestDeeperThanTheLimit_failsAsInvalid() {
        String deep = "<w:sdt>".repeat(DocxTextExtractor.MAX_ELEMENT_DEPTH + 10) + "</w:sdt>".repeat(DocxTextExtractor.MAX_ELEMENT_DEPTH + 10);
        String shallow = "<w:sdt>".repeat(50) + "</w:sdt>".repeat(50);
        assertReason(() -> extractor.extract(TestDocx.document(p("x") + deep)), DocumentFailureReason.INVALID_FILE);
        assertThat(extractor.extract(TestDocx.document(p("x") + shallow))).containsExactly("x");
    }

    /** Review B1: a row of countless empty cells costs heap without text; it is refused at the cell limit. */
    @Test
    void extract_whenATableRowHasTooManyCells_failsAsUnsupported() {
        String cells = "<w:tc/>".repeat(DocxTextExtractor.MAX_CELLS + 1);
        assertReason(() -> extractor.extract(TestDocx.document(p("x") + "<w:tbl><w:tr>" + cells + "</w:tr></w:tbl>")),
                DocumentFailureReason.UNSUPPORTED_FILE);
    }

    @Test
    void extract_whenTheStrictOoxmlNamespaceIsUsed_readsItToo() {
        String xml = "<w:document xmlns:w=\"http://purl.oclc.org/ooxml/wordprocessingml/main\"><w:body>"
                + p("katı biçim") + "</w:body></w:document>";

        assertThat(extractor.extract(TestDocx.zip(Map.of("word/document.xml", xml)))).containsExactly("katı biçim");
    }

    /** XXE: an external entity must neither be read nor make the file look valid (llm-rules 3.2, reference 9). */
    @Test
    void extract_whenTheXmlDeclaresExternalOrExpandingEntities_refusesWithoutReadingThem(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "MarkerSecretFromDisk");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE w:document [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>"
                + "<w:document xmlns:w=\"" + TestDocx.W + "\"><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>";
        String laughs = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;&a;&a;&a;&a;\">"
                + "<!ENTITY c \"&b;&b;&b;&b;&b;&b;&b;&b;\">]><w:document xmlns:w=\"" + TestDocx.W
                + "\"><w:body><w:p><w:r><w:t>&c;</w:t></w:r></w:p></w:body></w:document>";

        for (String xml : List.of(xxe, laughs)) {
            assertThatThrownBy(() -> extractor.extract(TestDocx.zip(Map.of("word/document.xml", xml))))
                    .isInstanceOfSatisfying(IngestionRejectedException.class,
                            e -> assertThat(e.reason()).isEqualTo(DocumentFailureReason.INVALID_FILE))
                    .hasMessageNotContaining("MarkerSecretFromDisk");
        }
    }

    @Test
    void extract_whenTheBytesAreNotAWordFile_failsAsInvalid() {
        Map<String, String> noMainPart = Map.of("word/other.xml", TestDocx.documentXml(p("x")));
        for (byte[] file : List.of("not a zip".getBytes(StandardCharsets.UTF_8), TestDocx.zip(noMainPart),
                TestDocx.zip(Map.of("word/document.xml", "<w:document xmlns:w=\"" + TestDocx.W + "\"><w:body>")))) {
            assertReason(() -> extractor.extract(file), DocumentFailureReason.INVALID_FILE);
        }
    }

    @Test
    void extract_whenTheStructureIsAmbiguousOrHuge_failsAsUnsupported() {
        assertReason(() -> extractor.extract(TestDocx.twoMainParts(p("görünen"), p("gizli"))),
                DocumentFailureReason.UNSUPPORTED_FILE);

        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i <= DocxTextExtractor.MAX_ENTRIES; i++) many.put("media/" + i + ".bin", new byte[] {1});
        many.put("word/document.xml", TestDocx.documentXml(p("x")).getBytes(StandardCharsets.UTF_8));
        assertReason(() -> extractor.extract(TestDocx.zipBytes(many)), DocumentFailureReason.UNSUPPORTED_FILE);
    }

    /** ZIP bomb: a part that inflates beyond the whole file's budget stops inflating, before or after the main part. */
    @Test
    void extract_whenAPartInflatesBeyondTheBudget_stopsAtTheBudget() {
        DocxTextExtractor small = new DocxTextExtractor(limits(DataSize.ofKilobytes(64), 2_000_000));
        Map<String, byte[]> bomb = new LinkedHashMap<>();
        bomb.put("media/zeros.bin", new byte[200 * 1024]);
        bomb.put("word/document.xml", TestDocx.documentXml(p("x")).getBytes(StandardCharsets.UTF_8));

        assertReason(() -> small.extract(TestDocx.zipBytes(bomb)), DocumentFailureReason.UNSUPPORTED_FILE);

        // Review T1: the budget is shared; parts that each fit can still exceed it together.
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) many.put("media/zeros" + i + ".bin", new byte[60 * 1024]);
        many.put("word/document.xml", TestDocx.documentXml(p("x")).getBytes(StandardCharsets.UTF_8));
        assertReason(() -> small.extract(TestDocx.zipBytes(many)), DocumentFailureReason.UNSUPPORTED_FILE);
    }

    @Test
    void extract_whenTheMainPartOrItsTextIsTooLarge_failsWithTooMuchText() {
        DocxTextExtractor small = new DocxTextExtractor(limits(DataSize.ofKilobytes(8), 2_000_000));
        String big = "<w:p>" + "<w:r><w:t>a</w:t></w:r>".repeat(2_000) + "</w:p>";
        assertReason(() -> small.extract(TestDocx.document(big)), DocumentFailureReason.TOO_MUCH_TEXT);

        DocxTextExtractor fewChars = new DocxTextExtractor(limits(DataSize.ofMegabytes(64), 100));
        assertReason(() -> fewChars.extract(docx(p("x".repeat(101)))), DocumentFailureReason.TOO_MUCH_TEXT);
    }

    @Test
    void extract_whenThereIsNoText_failsWithNoText() {
        assertReason(() -> extractor.extract(docx(p("   "), table(new String[] {"", ""}))), DocumentFailureReason.NO_TEXT);
    }

    private static DocumentProperties limits(DataSize maxContent, int maxTextChars) {
        return TestPdfs.properties(500, maxTextChars, 50_000, maxContent, 1000, 150);
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, DocumentFailureReason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(IngestionRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
