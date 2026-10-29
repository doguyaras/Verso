package com.verso.document.worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The context header of a chunk (ADR-0061): the document's title and the section or article the chunk sits in. It is
 * index input only: the embedding model reads header + blank line + chunk text, and the full-text index reads the
 * header next to the chunk text (document_chunk.header_vector). The stored chunk text, every excerpt, citation, prompt
 * and API response stay the chunk's own text. Without it, ninety near-identical regulations give ninety
 * indistinguishable "müdür üç yıl için görevlendirilir" chunks.
 *
 * <p>Deterministic and computed from what is stored (page texts, chunk texts, the document's title), so the backfill
 * builds the same header for an old document that ingestion builds for a new one. Bump {@link #VERSION} whenever the
 * output changes: the backfill then re-indexes every document ({@link #indexVersion}).
 */
public final class ChunkContext {

    /** Version of the header rules. Bump it whenever their output changes. */
    public static final int VERSION = 1;
    /** Short enough not to dominate a 1000-character chunk; the database checks it too (V7). */
    public static final int MAX_HEADER_CHARS = 200;
    /** Between header and chunk text in the embedding input; the header itself never contains it. */
    public static final String SEPARATOR = "\n\n";
    static final int MAX_SECTION_CHARS = 90;
    /** A heading is a short line; the article title above "MADDE n" is shorter still. */
    private static final int MAX_HEADING_CHARS = 90;
    private static final int MAX_ARTICLE_TITLE_CHARS = 70;
    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    /** "MADDE 7 –", "Madde 12-", "GEÇİCİ MADDE 1 (1)", "EK MADDE 2"; not a sentence like "Madde 5'e göre". */
    private static final Pattern ARTICLE =
            Pattern.compile("(?iu)^((?:geçici\\s+|ek\\s+)?madde\\s+\\d+[a-z]?)\\s*(?:[–—-]|\\(|$).*");
    /** "BİRİNCİ BÖLÜM", "İKİNCİ KISIM", "Bölüm 3". */
    private static final Pattern PART = Pattern.compile("(?iu)^(?:(?:birinci|ikinci|üçüncü|dördüncü|beşinci|altıncı|"
            + "yedinci|sekizinci|dokuzuncu|onuncu)\\s+(?:bölüm|kısım)|(?:bölüm|kısım)\\s+[\\divxlc]+)\\b.*");
    /** A Markdown heading of the docling sidecar or an MD file. */
    private static final Pattern MARKDOWN = Pattern.compile("^\\s*#{1,6}\\s+(.+)$");
    /** "3.2 Kullanıcı Yönetimi", "4. Sorumluluklar": a numbered heading, not a numbered sentence. */
    private static final Pattern NUMBERED = Pattern.compile("^\\d+(?:\\.\\d+){0,3}\\.?\\s+\\p{Lu}.*");

    /** A chunk as the header needs it: its page and its text. */
    public record Located(int pageNumber, String content) {
    }

    /** A heading found on a page: where its line starts and its text. */
    record Heading(int offset, String text) {
    }

    private ChunkContext() {}

    /**
     * One header per chunk, in the order given. Chunks are in document order (chunk index), each on its page; a heading
     * carries over to the next pages until another one starts, as an article runs over a page break.
     */
    public static List<String> headers(String title, List<String> pages, List<Located> chunks) {
        String folded = fold(oneLine(title));
        List<List<Heading>> headings = new ArrayList<>(pages.size());
        // The capital title lines of the first page are the title, not a section: they would only repeat it.
        for (String page : pages) {
            headings.add(headings(page).stream().filter(h -> !folded.contains(fold(h.text()))).toList());
        }
        List<String> headers = new ArrayList<>(chunks.size());
        int page = 0;
        int cursor = 0;
        String carried = null;
        for (Located chunk : chunks) {
            int number = chunk.pageNumber();
            if (number < 1 || number > pages.size()) {
                headers.add(header(title, null));
                continue;
            }
            while (page < number) {
                if (page >= 1) carried = last(headings.get(page - 1), Integer.MAX_VALUE, carried);
                page++;
                cursor = 0;
            }
            String text = pages.get(number - 1);
            int offset = locate(text, chunk.content(), cursor);
            if (offset >= 0) cursor = offset;
            // The heading in force a third into the chunk: a chunk that opens with "Müdür / MADDE 7" is about article 7.
            int reach = offset < 0 ? cursor : offset + Math.max(1, chunk.content().length() / 3);
            headers.add(header(title, last(headings.get(number - 1), reach, carried)));
        }
        return headers;
    }

    /** "title" or "title\nsection", at most {@link #MAX_HEADER_CHARS}; the section is cut or dropped first. */
    public static String header(String title, String section) {
        String head = oneLine(title);
        if (head.length() > MAX_HEADER_CHARS) head = cut(head, MAX_HEADER_CHARS);
        if (section == null) return head;
        String sec = oneLine(section);
        // A capital heading line of the first page is often a piece of the title itself.
        if (sec.isEmpty() || fold(head).contains(fold(sec))) return head;
        int room = MAX_HEADER_CHARS - head.length() - 1;
        if (room < 12) return head;
        if (sec.length() > room) sec = cut(sec, room);
        return head + "\n" + sec;
    }

    /**
     * What document.context_version stores: the rules' version and whether the header went into the embedding input
     * (2·VERSION) or only into the full-text index (2·VERSION − 1; verso.document.context-header.embed-header=false).
     * A document with any other value, NULL included, is re-indexed by the backfill.
     */
    public static int indexVersion(boolean embedHeader) {
        return 2 * VERSION - (embedHeader ? 0 : 1);
    }

    /** What the embedding model reads for a chunk: header, blank line, chunk text; the text alone without a header. */
    public static String embeddingInput(String header, String content, boolean embedHeader) {
        return !embedHeader || header == null || header.isEmpty() ? content : header + SEPARATOR + content;
    }

    /** The headings of one page, in order, each at the offset of its first line. */
    static List<Heading> headings(String page) {
        List<Heading> found = new ArrayList<>();
        String previous = null;
        int previousOffset = -1;
        int offset = 0;
        for (String raw : page.split("\n", -1)) {
            String line = raw.strip();
            int lineOffset = offset;
            offset += raw.length() + 1;
            if (line.isEmpty()) continue;
            Matcher article = ARTICLE.matcher(line);
            Matcher markdown = MARKDOWN.matcher(raw);
            if (article.matches()) {
                String number = capitalise(article.group(1).replaceAll("\\s+", " "));
                // A regulation names an article on the line above it ("Müdür" / "MADDE 7 – (1) ...").
                boolean titled = previous != null && articleTitle(previous);
                found.add(new Heading(titled ? previousOffset : lineOffset,
                        titled ? number + " – " + previous : number));
            } else if (markdown.matches()) {
                found.add(new Heading(lineOffset, oneLine(markdown.group(1))));
            } else if (line.length() <= MAX_HEADING_CHARS && !line.contains("|") && (PART.matcher(line).matches()
                    || (NUMBERED.matcher(line).matches() && !sentence(line))
                    || (DocumentTitle.capitals(line) && line.split(" ").length >= 2 && !sentence(line)))) {
                found.add(new Heading(lineOffset, line));
            }
            previous = line;
            previousOffset = lineOffset;
        }
        return found;
    }

    /** The text of the last heading starting before {@code reach}, else the one carried from earlier pages. */
    private static String last(List<Heading> headings, int reach, String carried) {
        String text = carried;
        for (Heading h : headings) {
            if (h.offset() >= reach) break;
            text = h.text();
        }
        return text;
    }

    /**
     * Where the chunk starts in its page: the chunker strips and, for tables, rebuilds lines, so the first line of the
     * chunk is searched, from the previous chunk's start on (chunks overlap). -1 when not found.
     */
    static int locate(String page, String content, int from) {
        String first = content.strip();
        int newline = first.indexOf('\n');
        if (newline > 0) first = first.substring(0, newline).strip();
        if (first.isEmpty()) return -1;
        if (first.length() > 80) first = first.substring(0, 80);
        int at = page.indexOf(first, Math.min(from, page.length()));
        return at >= 0 ? at : page.indexOf(first);
    }

    private static boolean articleTitle(String line) {
        return line.length() <= MAX_ARTICLE_TITLE_CHARS && Character.isLetter(line.charAt(0))
                && !sentence(line) && !ARTICLE.matcher(line).matches() && !line.contains("|");
    }

    private static boolean sentence(String line) {
        return ".,;:!?".indexOf(line.charAt(line.length() - 1)) >= 0;
    }

    private static String capitalise(String text) {
        String lower = text.toLowerCase(TURKISH);
        return lower.substring(0, 1).toUpperCase(TURKISH) + lower.substring(1);
    }

    /** Case and dot folding for comparing a capital line with a title-cased one, whichever letter rules each used. */
    private static String fold(String text) {
        return text.toLowerCase(Locale.ROOT).replace("̇", "").replace('ı', 'i');
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    /** At most {@code max} chars, cut at a word when one ends in the second half; never half a surrogate pair. */
    static String cut(String text, int max) {
        if (text.length() <= max) return text;
        int space = text.lastIndexOf(' ', max);
        int end = space > max / 2 ? space : max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).strip();
    }
}
