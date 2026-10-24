package com.verso.qa.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rule-based sentence boundaries for Turkish text (ADR-0019), used to pick the excerpt window of a citation and to find
 * the answer sentence that carries a marker. Deterministic and allocation-light: it runs on the hot path, after the
 * chat call, for every cited passage.
 *
 * <p>A sentence ends at {@code .}, {@code !}, {@code ?} or {@code …} (closing quotes and brackets included) followed by
 * white space and a character that is not a lower-case letter. A period does not end a sentence after a known
 * abbreviation ({@code Dr.}, {@code vb.}, {@code bkz.}), a single letter ({@code A. Yılmaz}, {@code s. 12}), a dotted
 * abbreviation ({@code A.Ş.}, {@code T.C.}) or a short number ({@code 3. madde}, {@code 2. Dünya}: an ordinal). Dots
 * inside numbers ({@code 25.000 TL}, {@code 3.2}) are never followed by white space, so they never split.
 */
final class SentenceSplitter {

    /** A sentence as {@code [start, end)} in the split text, leading and trailing white space excluded. */
    record Span(int start, int end) {
    }

    private static final Locale TURKISH = Locale.forLanguageTag("tr");

    // Lower-cased with the Turkish locale, without the final dot: common abbreviations in official and business
    // documents. Words that also end sentences as ordinary words ("kat", "ara", "bay") are left out on purpose. A
    // missing abbreviation only splits one sentence in two; the window of two sentences usually rejoins it.
    private static final Set<String> ABBREVIATIONS = Set.of(
            "dr", "prof", "doç", "yrd", "öğr", "gör", "uzm", "av", "müh", "sn", "hz",
            "vb", "vs", "vd", "bkz", "krş", "örn", "ör", "akt", "çev", "haz", "ed", "yay",
            "sy", "sf", "no", "nr", "md", "mad", "fık", "bnd", "böl", "syf",
            "ltd", "şti", "inc", "co", "tic", "koll", "gn", "gnl", "müd", "bşk", "başk", "yön",
            "cad", "sok", "mah", "apt", "blv", "tel", "faks", "fax", "pk",
            "şub", "tem", "ağu", "eyl", "yy", "mr", "mrs", "ms", "jr", "st", "etc", "vol", "pp", "fig");
    private static final Pattern ROMAN = Pattern.compile("[IVX]{1,4}");

    private SentenceSplitter() {
    }

    static List<Span> split(String text) {
        return split(text, false);
    }

    /**
     * @param lineBreaks true for the model's answer, where a line break separates list items; false for passages,
     *                   where text extraction breaks lines inside sentences
     */
    static List<Span> split(String text, boolean lineBreaks) {
        List<Span> sentences = new ArrayList<>();
        int length = text.length();
        int start = skipSpace(text, 0);
        int i = start;
        while (i < length) {
            char c = text.charAt(i);
            if (lineBreaks && (c == '\n' || c == '\r')) {
                add(sentences, text, start, i);
                start = skipSpace(text, i);
                i = start;
                continue;
            }
            if (!isTerminator(c)) {
                i++;
                continue;
            }
            int end = i + 1;
            while (end < length && (isTerminator(text.charAt(end)) || isCloser(text.charAt(end)))) end++;
            int next = skipSpace(text, end);
            // Only a lone dot (closing quotes aside) can belong to an abbreviation; "..." and "?!" always end.
            boolean loneDot = c == '.' && closers(text, i + 1, end) == end - i - 1;
            boolean boundary = end < length && Character.isWhitespace(text.charAt(end)) && next < length
                    && !Character.isLowerCase(text.codePointAt(next))
                    && !(loneDot && continuesAfterDot(text, start, i));
            if (boundary) {
                add(sentences, text, start, end);
                start = next;
                i = next;
            } else {
                i = end;
            }
        }
        add(sentences, text, start, length);
        return sentences;
    }

    /** True when the single dot at {@code dot} closes an abbreviation, an initial or an ordinal, not a sentence. */
    private static boolean continuesAfterDot(String text, int sentenceStart, int dot) {
        int from = dot;
        while (from > sentenceStart && !Character.isWhitespace(text.charAt(from - 1))) from--;
        while (from < dot && !Character.isLetterOrDigit(text.charAt(from))) from++;
        if (from == dot) return false;
        String word = text.substring(from, dot);
        if (word.codePointCount(0, word.length()) == 1 && Character.isLetter(word.codePointAt(0))) return true;
        if (word.chars().allMatch(Character::isDigit)) return word.length() <= 3;
        if (ROMAN.matcher(word).matches()) return true;
        if (word.indexOf('.') > 0 && isDottedAbbreviation(word)) return true;
        return ABBREVIATIONS.contains(word.toLowerCase(TURKISH));
    }

    /** {@code A.Ş}, {@code T.C}, {@code M.Ö}: short letter groups joined by dots (the final dot is not included). */
    private static boolean isDottedAbbreviation(String word) {
        for (String part : word.split("\\.", -1)) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isLetter)) return false;
        }
        return true;
    }

    private static int closers(String text, int from, int to) {
        int count = 0;
        for (int k = from; k < to; k++) if (isCloser(text.charAt(k))) count++;
        return count;
    }

    private static void add(List<Span> sentences, String text, int start, int end) {
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (end > start) sentences.add(new Span(start, end));
    }

    private static int skipSpace(String text, int from) {
        while (from < text.length() && Character.isWhitespace(text.charAt(from))) from++;
        return from;
    }

    private static boolean isTerminator(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    private static boolean isCloser(char c) {
        return c == '"' || c == '\'' || c == '”' || c == '’' || c == '»' || c == ')' || c == ']';
    }
}
