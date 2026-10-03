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
