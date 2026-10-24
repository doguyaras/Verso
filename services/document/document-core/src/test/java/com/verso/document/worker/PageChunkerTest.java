package com.verso.document.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.PageChunker.Chunk;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** llm-rules 6.3 and 6.4: chunks stay inside one page, respect the size and overlap, and keep their page number. */
class PageChunkerTest {

    @Test
    void chunk_whenPagesAreLong_neverCrossesAPageAndRespectsTheSize() {
        PageChunker chunker = new PageChunker(TestPdfs.properties(500, 2_000_000, 100, 20));
        String page1 = words("alpha", 60);
        String page2 = words("beta", 60);
        List<Chunk> chunks = chunker.chunk(List.of(page1, page2));

        assertThat(chunks).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(100));
        assertThat(chunks).filteredOn(c -> c.pageNumber() == 1).allSatisfy(c -> assertThat(c.content()).doesNotContain("beta"));
        assertThat(chunks).filteredOn(c -> c.pageNumber() == 2).allSatisfy(c -> assertThat(c.content()).doesNotContain("alpha"));
        assertThat(chunks).extracting(Chunk::chunkIndex).containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
    }

    @Test
    void chunk_whenWordsFitTheWindow_cutsAtWhitespaceAndOverlaps() {
        PageChunker chunker = new PageChunker(TestPdfs.properties(500, 2_000_000, 100, 20));
        List<Chunk> chunks = chunker.chunk(List.of(words("word", 60)));
        assertThat(chunks).hasSizeGreaterThan(1);
        for (Chunk chunk : chunks) {
            assertThat(chunk.content()).as("no split word").matches("(word\\d+ ?)+");
        }
        // Neighbours share text: the last word of one chunk appears in the next one.
        for (int i = 1; i < chunks.size(); i++) {
            String[] previous = chunks.get(i - 1).content().split(" ");
            assertThat(chunks.get(i).content()).contains(previous[previous.length - 1]);
        }
    }

    @Test
    void chunk_whenEveryWordIsKept_coversThePageText() {
        PageChunker chunker = new PageChunker(TestPdfs.properties(500, 2_000_000, 100, 20));
        String page = words("token", 80);
        String joined = chunker.chunk(List.of(page)).stream().map(Chunk::content).collect(Collectors.joining(" "));
        for (String word : page.split(" ")) assertThat(joined).contains(word);
    }

    /** Test review T16: when the overlap would start inside a word, the next chunk starts at the next word. */
    @Test
    void chunk_whenTheOverlapStartsMidWord_startsTheNextChunkAtAWordBoundary() {
        PageChunker chunker = new PageChunker(TestPdfs.properties(500, 2_000_000, 50, 13));
        String text = "aa bbbbbbbbbbbbbbbbbbbb cc dddddddddddddddddddddddd ee ffffffffffffffffffffff gg hhhhhhhhhhhh ii";
        List<Chunk> chunks = chunker.chunk(List.of(text));
        assertThat(chunks).hasSizeGreaterThan(1);
        java.util.Set<String> words = java.util.Set.of(text.split(" "));
        for (Chunk chunk : chunks) {
            String first = chunk.content().split(" ")[0];
            assertThat(words).as("chunk starts with a whole word: " + chunk.content()).contains(first);
        }
    }

    @Test
    void chunk_whenPagesAreEmptyOrShort_skipsEmptyAndKeepsPageNumbers() {
        PageChunker chunker = new PageChunker(TestPdfs.properties());
        List<Chunk> chunks = chunker.chunk(List.of("", "short text", "   ", "another"));
        assertThat(chunks).extracting(Chunk::pageNumber).containsExactly(2, 4);
        assertThat(chunks).extracting(Chunk::chunkIndex).containsExactly(0, 1);
    }

    @Test
    void chunk_whenAWordIsLongerThanTheWindow_stillTerminatesAndCutsIt() {
        PageChunker chunker = new PageChunker(TestPdfs.properties(500, 2_000_000, 100, 20));
        List<Chunk> chunks = chunker.chunk(List.of("x".repeat(350)));
        assertThat(chunks).isNotEmpty().allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(100));
        assertThat(chunks.stream().mapToInt(c -> c.content().length()).sum()).isGreaterThanOrEqualTo(350);
    }

    /** ADR-0032: a Markdown table that fits is one chunk, with the line before it (its heading) in front. */
    @Test
    void chunk_whenATableFits_keepsItWholeWithItsHeading() {
        PageChunker chunker = new PageChunker(TestPdfs.properties(500, 2_000_000, 300, 40));
        String page = words("giriş", 30) + "\n3.2 Hız sınırları\n| Alan | Azami hız |\n| --- | --- |\n"
                + "| Raf koridorları | 6 km/sa |\n| Dış saha | 15 km/sa |\nTablodan sonra gelen paragraf.";
        List<Chunk> chunks = chunker.chunk(List.of(page));
        assertThat(chunks).filteredOn(c -> c.content().contains("| Alan |")).singleElement().satisfies(c ->
                assertThat(c.content()).isEqualTo("3.2 Hız sınırları\n| Alan | Azami hız |\n| --- | --- |\n"
                        + "| Raf koridorları | 6 km/sa |\n| Dış saha | 15 km/sa |"));
        assertThat(chunks.getLast().content()).isEqualTo("Tablodan sonra gelen paragraf.");
        assertThat(chunks).allSatisfy(c -> assertThat(c.pageNumber()).isOne());
        assertThat(chunks).extracting(Chunk::chunkIndex).containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
    }

    /** ADR-0032: a longer table is split between rows; every part repeats the header row and no row is lost or cut. */
    @Test
    void chunk_whenATableIsLongerThanAChunk_repeatsTheHeaderInEveryPart() {
        PageChunker chunker = new PageChunker(TestPdfs.properties(500, 2_000_000, 200, 30));
        StringBuilder table = new StringBuilder("Durum kodları\n| Kod | Anlamı |\n| --- | --- |");
        List<String> rows = IntStream.range(0, 30).mapToObj(i -> "| SAT-E" + (100 + i) + " | Açıklama " + i + " |").toList();
        rows.forEach(r -> table.append('\n').append(r));
        List<Chunk> chunks = chunker.chunk(List.of(table.toString(), "ikinci sayfa"));
        List<Chunk> parts = chunks.stream().filter(c -> c.pageNumber() == 1).toList();
        assertThat(parts).hasSizeGreaterThan(1).allSatisfy(c -> {
            assertThat(c.content()).startsWith("Durum kodları\n| Kod | Anlamı |\n| --- | --- |\n");
            assertThat(c.content().length()).isLessThanOrEqualTo(200);
        });
        List<String> found = parts.stream().flatMap(c -> c.content().lines().skip(3)).toList();
        assertThat(found).containsExactlyElementsOf(rows);
        assertThat(chunks.getLast()).isEqualTo(new Chunk(2, chunks.size() - 1, "ikinci sayfa"));
    }

    /**
     * The JDK path is unchanged (ADR-0032): pipes without a Markdown delimiter row ("Alan | Azami hız", as the JDK
     * parser reads the samples) are chunked exactly like any other text of the same length.
     */
    @Test
    void chunk_whenPipesAreNotAMarkdownTable_chunksLikePlainText() {
        PageChunker chunker = new PageChunker(TestPdfs.properties(500, 2_000_000, 100, 20));
        String page = "Alan | Azami hız\nRaf koridorları | 6 km/sa\n" + words("metin", 40) + "\nDış saha | 15 km/sa";
        List<String> withPipes = chunker.chunk(List.of(page)).stream().map(Chunk::content).toList();
        List<String> withoutPipes = chunker.chunk(List.of(page.replace('|', '¦'))).stream()
                .map(c -> c.content().replace('¦', '|')).toList();
        assertThat(withPipes).hasSizeGreaterThan(1).isEqualTo(withoutPipes);
    }

    private static String words(String stem, int count) {
        return IntStream.range(0, count).mapToObj(i -> stem + i).collect(Collectors.joining(" "));
    }
}
