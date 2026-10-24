// Dumps the sample PDFs as the ingestion worker would chunk them (PdfTextExtractor settings, TextNormalizer,
// PageChunker with chunk-size 1000 / overlap 150), as JSON lines. Used by eval/embedding-olcumu/olcum.mjs.
// Run (JDK 25, PDFBox 3.0.8 jars on the class path): java -cp <jars> ChunkDump.java samples > chunks.jsonl
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public class ChunkDump {
    static final int SIZE = 1000, OVERLAP = 150;
    static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t\\n]]");
    static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\u00A0]+");
    static final Pattern BLANK = Pattern.compile("\\n\\s*\\n+");

    static String normalize(String t) {
        String c = CONTROL.matcher(t.replace("\r\n", "\n").replace('\r', '\n')).replaceAll(" ");
        c = SPACES.matcher(c).replaceAll(" ");
        c = BLANK.matcher(c).replaceAll("\n\n");
        return c.strip();
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                default -> { if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
            }
        }
        return b.append('"').toString();
    }

    static int lastWs(String t, int from, int to) {
        for (int i = to; i > from; i--) if (Character.isWhitespace(t.charAt(i - 1))) return i - 1;
        return -1;
    }

    public static void main(String[] args) throws Exception {
        List<Path> pdfs;
        try (var s = Files.list(Path.of(args[0]))) { pdfs = s.filter(p -> p.toString().endsWith(".pdf")).sorted().toList(); }
        var out = new java.io.PrintStream(System.out, true, "UTF-8");
        for (Path pdf : pdfs) {
            List<String> pages = new ArrayList<>();
            try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(pdf))) {
                PDFTextStripper st = new PDFTextStripper();
                st.setSortByPosition(true);
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    st.setStartPage(p);
                    st.setEndPage(p);
                    pages.add(normalize(st.getText(doc)));
                }
            }
            int idx = 0;
            for (int p = 0; p < pages.size(); p++) {
                String text = pages.get(p);
                int start = 0;
                while (start < text.length()) {
                    int end = Math.min(text.length(), start + SIZE);
                    if (end < text.length()) {
                        int sp = lastWs(text, start + SIZE / 2, end);
                        if (sp > start) end = sp;
                    }
                    String content = text.substring(start, end).strip();
                    if (!content.isEmpty())
                        out.println("{\"file\":" + json(pdf.getFileName().toString()) + ",\"page\":" + (p + 1)
                                + ",\"idx\":" + (idx++) + ",\"content\":" + json(content) + "}");
                    if (end >= text.length()) break;
                    start = Math.max(end - OVERLAP, start + 1);
                    while (start < end && !Character.isWhitespace(text.charAt(start - 1))) start++;
                }
            }
        }
    }
}
