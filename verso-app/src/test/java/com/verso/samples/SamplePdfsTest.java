package com.verso.samples;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.PdfTextExtractor;
import com.verso.support.VersoPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * samples/*.pdf are built from samples/src/*.txt and must stay in step with them (the eval set and the demo use the
 * PDFs, people edit the text). Regenerate after editing a source:
 *
 * <pre>./mvnw -pl verso-app -am test -Dtest=SamplePdfsTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dverso.samples.write=true</pre>
 */
class SamplePdfsTest {

    private static final Path SAMPLES = VersoPostgres.repoRoot().resolve("samples");

    @Test
    void samples_whenBuiltFromTheirSources_matchTheCommittedPdfs() throws IOException {
        List<Path> sources = sources();
        assertThat(sources).hasSizeGreaterThanOrEqualTo(6);
        boolean write = Boolean.getBoolean("verso.samples.write");
        for (Path source : sources) {
            String name = source.getFileName().toString().replace(".txt", ".pdf");
            byte[] built = SamplePdfs.build(name, Files.readString(source, StandardCharsets.UTF_8));
            Path pdf = SAMPLES.resolve(name);
            if (write) Files.write(pdf, built);
            assertThat(pdf).as(name + " is missing or stale: regenerate (see the class comment)").exists();
            assertThat(Files.readAllBytes(pdf)).as(name + " is stale: regenerate (see the class comment)").isEqualTo(built);
        }
    }

    /** What the eval set asks about must come out of the PDFs as Turkish text, letter for letter (ğ, ı, ş, İ, Ş, Ğ). */
    @Test
    void samples_whenExtractedLikeIngestion_giveBackTheTurkishText() throws IOException {
        PdfTextExtractor extractor = new PdfTextExtractor(TestPdfs.properties());
        for (Path source : sources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8).replace("\r", "");
            List<String> pages = extractor.extract(SamplePdfs.build(source.getFileName().toString(), text));
            assertThat(pages).as(source.getFileName().toString()).hasSize(text.split("\n---\n").length);
            String all = String.join(" ", pages).replaceAll("\\s+", " ");
            for (String sentence : text.split("\n")) {
                // The whole line, not a prefix: a fact at the end of a line must survive too (phase 8 review T4).
                if (sentence.isBlank() || sentence.equals("---")) continue;
                String probe = sentence.replaceAll("\\s+", " ").strip();
                assertThat(all).as(source.getFileName() + ": " + probe).contains(probe);
            }
        }
        String izin = String.join(" ", extractor.extract(Files.readAllBytes(SAMPLES.resolve("izin-yonetmeligi.pdf"))));
        assertThat(izin).contains("yirmi altı iş günü", "Doğum izninin", "İnsan Kaynakları Direktörü", "Taşınma");
    }

    /**
     * The measurement variants of ADR-0032, built from the same sources: ruled tables (GRID) keep every page and every
     * cell text; the scanned copy has the same pages and no text layer at all. With -Dverso.samples.variants=DIR all of
     * them are written to DIR/grid and DIR/scanned (not committed: rendering depends on the host's fonts).
     */
    @Test
    void variants_whenBuilt_keepThePages_andTheScannedCopyHasNoText() throws IOException {
        PdfTextExtractor extractor = new PdfTextExtractor(TestPdfs.properties());
        String target = System.getProperty("verso.samples.variants");
        for (Path source : sources()) {
            String name = source.getFileName().toString().replace(".txt", ".pdf");
            String text = Files.readString(source, StandardCharsets.UTF_8).replace("\r", "");
            byte[] grid = SamplePdfs.build(name, text, SamplePdfs.Tables.GRID);
            List<String> pages = extractor.extract(grid);
            assertThat(pages).as(name).hasSize(text.split("\n---\n").length);
            String all = String.join(" ", pages).replaceAll("\\s+", " ");
            for (String row : text.split("\n")) {
                if (!row.contains(" | ")) continue;
                for (String cell : row.split(" \\| ")) {
                    // Wrapped cells come out word by word; the words are all there.
                    for (String word : cell.strip().split("\\s+")) assertThat(all).as(name + ": " + word).contains(word);
                }
            }
            assertThat(all).as(name + ": no pipes in a ruled table").doesNotContain(" | ");
            boolean writeAll = target != null;
            if (!writeAll && !name.equals("isg-depo-proseduru.pdf")) continue;
            byte[] scanned = SamplePdfs.scanned(grid, 200);
            assertThat(extractor.read(scanned)).as(name + " scanned").hasSize(pages.size()).allMatch(String::isEmpty);
            if (writeAll) {
                Files.createDirectories(Path.of(target, "grid"));
                Files.createDirectories(Path.of(target, "scanned"));
                Files.write(Path.of(target, "grid", name), grid);
                Files.write(Path.of(target, "scanned", name), scanned);
            }
        }
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.list(SAMPLES.resolve("src"))) {
            return files.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
    }
}
