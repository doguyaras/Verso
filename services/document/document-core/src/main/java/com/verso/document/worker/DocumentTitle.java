package com.verso.document.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The title a document's chunks are indexed under (ADR-0061): the heading lines at the top of its first page, else the
 * PDF metadata title, else the file name without its extension. Used only to build the context header of each chunk
 * (dense and full-text index); never shown, never logged (llm-rules 2.1), never part of a citation.
 *
 * <p>A heading written in capitals ("HACETTEPE ÜNİVERSİTESİ ... MERKEZİ (HÜBAUM) YÖNETMELİĞİ") is written in title case
 * (Turkish letter rules when the text has Turkish capitals), so its words match a question that writes them normally;
 * a word in parentheses is kept as written, because it is usually an abbreviation the question repeats verbatim.
 */
public final class DocumentTitle {

    /** Room for a regulation's full name; the header adds the section after it (ChunkContext.MAX_HEADER_CHARS). */
    static final int MAX_TITLE_CHARS = 150;
    /** A heading of the first page sits above the first article; lines further down are body text. */
    private static final int MAX_LINES = 20;
    /** A heading line is short; a long line is a paragraph. */
    private static final int MAX_LINE_CHARS = 160;
    /** Lines joined into one title: a long name wraps over two or three lines. */
    private static final int MAX_TITLE_LINES = 4;
    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    /** Where the body begins: an article, or a part heading ("BİRİNCİ BÖLÜM", "Bölüm 1"). */
    private static final Pattern BODY_START = Pattern.compile("(?iu)^(?:(?:geçici|ek)\\s+)?madde\\s+\\d+.*"
            + "|^(?:birinci|ikinci|üçüncü|dördüncü|beşinci|altıncı|yedinci|sekizinci|dokuzuncu|onuncu)\\s+(?:bölüm|kısım).*"
            + "|^(?:bölüm|kısım)\\s+[\\divxlc]+\\b.*");
    /** Gazette and letterhead lines above the title: dates, issue numbers, "T.C.", the issuer ("... Üniversitesinden:"). */
    private static final Pattern BOILERPLATE = Pattern.compile(
            "(?iu).*(?:resm[iî]\\s+gazete|tarih\\s*:|sayı\\s*:|sayfa\\s*\\d|\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}).*|.*:\\s*$"
                    + "|^t\\.\\s*c\\.?$");
    /** Short connectives written in lower case inside a title. */
    private static final Set<String> CONNECTIVES = Set.of("ve", "ile", "veya", "ya", "da", "de", "için", "ki", "bir");
    private static final Pattern PARENTHESISED = Pattern.compile("\\(([^()]{1,30})\\)");
    /**
     * Capitals that only Turkish writes. With one of them the Turkish rules apply (I → ı, İ → i); without, the text is
     * English or written without Turkish letters ("INFORMATION SECURITY", "YONETIMI"), where I is i.
     */
    private static final Pattern TURKISH_CAPITALS = Pattern.compile("[İŞĞÇÖÜ]");
    /** PDF metadata titles that a writer tool filled in, not the author. */
    private static final Pattern GENERATED_METADATA = Pattern.compile(
            "(?iu).*(?:microsoft\\s+word|untitled|başlıksız|adsız|\\.(?:docx?|pdf|odt|rtf|txt)\\s*$).*");

    private DocumentTitle() {}

    /**
     * The title for the context header; never null or blank. {@code metadataTitle} is the PDF's own title, known only
     * while the file is parsed (null afterwards and for other formats).
     */
    public static String of(List<String> pages, String metadataTitle, String fileName) {
        String heading = pages.isEmpty() ? null : fromFirstPage(pages.getFirst());
        if (heading != null) return heading;
        String metadata = fromMetadata(metadataTitle);
        if (metadata != null) return metadata;
        return fromFileName(fileName);
    }

    /**
     * The heading at the top of the first page: gazette and letterhead lines are skipped and the first other line
     * decides. A capital line is joined with the capital lines after it (a long name wraps over lines); a mixed-case
     * line counts when it reads as a heading; body text means the page has no heading of its own.
     */
    static String fromFirstPage(String page) {
        List<String> lines = topLines(page);
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            // A lone capital word ("YÖNETMELİK", "GİZLİ") above the title names no document.
            if (BOILERPLATE.matcher(line).matches() || (capitalLine(line) && line.indexOf(' ') < 0)) {
                i++;
                continue;
            }
            if (capitalLine(line)) {
                List<String> run = new ArrayList<>();
                while (i < lines.size() && run.size() < MAX_TITLE_LINES && capitalLine(lines.get(i))) {
                    run.add(lines.get(i++));
                }
                return bound(titleCase(String.join(" ", run)));
            }
            return looksLikeHeading(line) ? bound(line) : null;
        }
        return null;
    }

    private static boolean capitalLine(String line) {
        return line.length() <= MAX_LINE_CHARS && capitals(line) && !BOILERPLATE.matcher(line).matches();
    }

    static String fromMetadata(String title) {
        if (title == null) return null;
        String clean = collapse(title);
        if (letters(clean) < 4 || GENERATED_METADATA.matcher(clean).matches()) return null;
        return bound(capitals(clean) ? titleCase(clean) : clean);
    }

    /** "hubaum-yonetmeligi_2015.pdf" becomes "hubaum yonetmeligi 2015"; never empty. */
    static String fromFileName(String fileName) {
        String name = fileName == null ? "" : fileName.strip();
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 6) name = name.substring(0, dot);
        String clean = collapse(name.replaceAll("[_\\-.+]+", " "));
        return clean.isEmpty() ? "belge" : bound(clean);
    }

    private static List<String> topLines(String page) {
        List<String> lines = new ArrayList<>();
        for (String raw : page.split("\n")) {
            // docling writes headings as Markdown ("## Başlık") in some outputs; the marker is no part of the title.
            String line = collapse(raw.replaceFirst("^\\s*#{1,6}\\s+", ""));
            if (line.isEmpty()) continue;
            if (BODY_START.matcher(line).matches() || lines.size() >= MAX_LINES) break;
            lines.add(line);
        }
        return lines;
    }

    /** At least three letters, and at least four in five of them capitals. */
    static boolean capitals(String line) {
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isLetter(c)) continue;
            letters++;
            if (Character.isUpperCase(c)) upper++;
        }
        return letters >= 3 && upper * 5 >= letters * 4;
    }

    /** A short line that starts with a capital or a digit, has two words or more and does not end like a sentence. */
    private static boolean looksLikeHeading(String line) {
        if (line.length() > 120 || line.split(" ").length < 2) return false;
        char first = line.charAt(0);
        if (!Character.isUpperCase(first) && !Character.isDigit(first)) return false;
        char last = line.charAt(line.length() - 1);
        if (".,;:!?".indexOf(last) >= 0) return false;
        return letters(line) * 2 >= line.length();
    }

    /** "MERKEZİ (HÜBAUM) YÖNETMELİĞİ VE ESASLARI" → "Merkezi (HÜBAUM) Yönetmeliği ve Esasları". */
    static String titleCase(String capitals) {
        Locale locale = TURKISH_CAPITALS.matcher(capitals).find() ? TURKISH : Locale.ROOT;
        StringBuilder out = new StringBuilder(capitals.length());
        Matcher kept = PARENTHESISED.matcher(capitals);
        int at = 0;
        while (kept.find()) {
            out.append(titleCaseWords(capitals.substring(at, kept.start()), out.isEmpty(), locale));
            out.append(kept.group());
            at = kept.end();
        }
        out.append(titleCaseWords(capitals.substring(at), out.isEmpty(), locale));
        return out.toString();
    }

    private static String titleCaseWords(String text, boolean atStart, Locale locale) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        boolean first = atStart;
        while (i < text.length()) {
            if (!Character.isLetter(text.charAt(i))) {
                out.append(text.charAt(i++));
                continue;
            }
            int end = i;
            while (end < text.length() && (Character.isLetter(text.charAt(end)) || text.charAt(end) == '\'')) end++;
            String word = text.substring(i, end).toLowerCase(locale);
            if (first || !CONNECTIVES.contains(word)) {
                word = word.substring(0, 1).toUpperCase(locale) + word.substring(1);
            }
            out.append(word);
            first = false;
            i = end;
        }
        return out.toString();
    }

    /**
     * At most MAX_TITLE_CHARS, cut at a word; a parenthesised abbreviation the cut removed is appended again, because
     * a question names a centre by it ("HÜBAUM") and it must reach the full-text index.
     */
    static String bound(String title) {
        if (title.length() <= MAX_TITLE_CHARS) return title;
        List<String> lost = new ArrayList<>();
        Matcher m = PARENTHESISED.matcher(title);
        while (m.find()) if (m.end() > MAX_TITLE_CHARS) lost.add(m.group());
        String suffix = lost.isEmpty() ? "" : " " + String.join(" ", lost);
        if (suffix.length() > MAX_TITLE_CHARS / 3) suffix = " " + lost.getFirst();
        return (ChunkContext.cut(title, MAX_TITLE_CHARS - suffix.length()) + suffix).strip();
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static int letters(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) n++;
        return n;
    }
}
