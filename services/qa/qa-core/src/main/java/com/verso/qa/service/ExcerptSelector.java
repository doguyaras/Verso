package com.verso.qa.service;

import com.verso.qa.service.SentenceSplitter.Span;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the excerpt of a cited passage (ADR-0019): the window of one or two sentences that shares the most words with
 * the answer sentences citing it, trimmed to {@link #MAX} code points. Lexical and deterministic: no model or network
 * call, and small bounded work per passage (a passage is one chunk, about 1000 characters), so the hot path keeps its two
 * remote calls and their budget (ADR-0008).
 *
 * <p>The excerpt is always a substring of the retrieved passage text (white space collapsed), never model text
 * (llm-rules 3.3); the answer only decides where the window is. Display only, never logged (llm-rules 2.1).
 */
final class ExcerptSelector {

    /** Upper bound of {@code Citation.excerpt} in code points, ellipses included (API doc: at most 280). */
    static final int MAX = 280;
    static final String ELLIPSIS = "…";

    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    // Unicode white space (NBSP included) and control characters left over from text extraction become one space.
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{IsWhite_Space}\\p{Cc}]+");
    // A number keeps its inner separators (25.000, 3,2) so it stays one token; a word is a run of letters.
    private static final Pattern TOKEN = Pattern.compile("\\p{Nd}+(?:[.,]\\p{Nd}+)*|\\p{L}[\\p{L}\\p{M}]*");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    /** Words of at least this many letters match on their first {@value} letters: Turkish suffixes vary the rest. */
    static final int STEM = 5;
    /** A shared number (an amount, a day count, a date) is strong evidence for the supporting sentence. */
    static final int NUMBER_WEIGHT = 3;
    private static final int WORD_WEIGHT = 1;
    private static final int MAX_SENTENCES = 2;

    // Folded (see fold), before stemming: function words, and the model's own framing ("belgeye göre"), which every
    // sentence would share. Words of one letter are dropped anyway. English words that fold onto Turkish ones are
    // left out on purpose: "on" is ten, and "is" is "iş".
    private static final Set<String> STOP_WORDS = Set.of(
            "ve", "veya", "ile", "ya", "da", "de", "ki", "mi", "mu", "bir", "bu", "su", "icin", "gibi", "daha",
            "en", "cok", "her", "ise", "olan", "olarak", "kadar", "ancak", "ama", "fakat", "hem", "ayrica", "yani",
            "gore", "belge", "belgede", "belgeye", "belgelere", "belgelerde", "pasaj", "pasajda",
            "the", "of", "to", "and", "for", "with");

    private ExcerptSelector() {
    }

    /** A matched term in the passage: where it is and what it is worth. */
    private record Hit(int start, int end, String key, int weight) {
    }

    /** {@link #select(String, String, int)} for a passage the model saw whole. */
    static String select(String content, String citing) {
        return select(content, citing, Integer.MAX_VALUE);
    }

    /**
     * @param content the retrieved passage text
     * @param citing  the answer sentences that cite this passage, markers removed; blank means "no evidence"
     * @param seen    how many characters of {@code content} the prompt carried ({@code verso.qa.max-passage-chars}):
     *                only words in that part count as evidence, since the model cannot have relied on the rest
     * @return the best supporting window, or the passage start when no sentence shares a term with {@code citing}
     */
    static String select(String content, String citing, int seen) {
        if (content == null) return "";
        String text = WHITESPACE.matcher(content).replaceAll(" ").strip();
        // A passage that fits contains whatever supports the answer; showing all of it is the most faithful excerpt.
        if (codePoints(text, 0, text.length()) <= MAX) return text;
        Map<String, Integer> query = terms(citing == null ? "" : citing);
        if (query.isEmpty()) return cut(text, 0, text.length());

        // The collapsed prompt part is a prefix of the collapsed passage: collapsing and stripping keep prefixes.
        int seenEnd = seen >= content.length() ? text.length()
                : WHITESPACE.matcher(content.substring(0, seen)).replaceAll(" ").strip().length();
        List<Span> sentences = SentenceSplitter.split(text);
        List<List<Hit>> hits = new ArrayList<>(sentences.size());
        for (Span sentence : sentences) hits.add(hits(text, sentence.start(), Math.min(sentence.end(), seenEnd), query));

        int bestFrom = -1;
        int bestCount = 0;
        int bestScore = 0;
        for (int from = 0; from < sentences.size(); from++) {
            for (int count = 1; count <= MAX_SENTENCES && from + count <= sentences.size(); count++) {
                int score = score(hits.subList(from, from + count));
                // Ties go to the shorter window, then to the earlier one (iteration order): a second sentence that
                // adds no new term is not shown.
                if (score > bestScore || (score == bestScore && score > 0 && count < bestCount)) {
                    bestScore = score;
                    bestFrom = from;
                    bestCount = count;
                }
            }
        }
        if (bestScore == 0) return cut(text, 0, text.length());
        int from = sentences.get(bestFrom).start();
        int to = sentences.get(bestFrom + bestCount - 1).end();
        List<Hit> windowHits = new ArrayList<>();
        for (List<Hit> sentenceHits : hits.subList(bestFrom, bestFrom + bestCount)) windowHits.addAll(sentenceHits);
        return trim(text, from, to, windowHits);
    }

    /** Each distinct query term counts once per window, however often it occurs. */
    private static int score(List<List<Hit>> window) {
        Set<String> seen = new HashSet<>();
        int score = 0;
        for (List<Hit> sentenceHits : window) {
            for (Hit hit : sentenceHits) if (seen.add(hit.key())) score += hit.weight();
        }
        return score;
    }

    /**
     * A window longer than the limit keeps its start when that keeps the most evidence. Otherwise it starts at the word
     * of a hit, with an ellipsis marking the cut: the latest such start that still keeps the most query weight, so the
     * supporting words open the excerpt instead of being pushed towards its end.
     */
    private static String trim(String text, int from, int to, List<Hit> hits) {
        if (codePoints(text, from, to) <= MAX) return text.substring(from, to);
        int bestStart = from;
        int bestCovered = covered(text, from, to, hits, from);
        for (Hit hit : hits) {
            int start = wordStart(text, from, hit.start());
            if (start == from) continue;
            int covered = covered(text, start, to, hits, from);
            if (covered > bestCovered || (covered == bestCovered && bestStart != from)) {
                bestCovered = covered;
                bestStart = start;
            }
        }
        return bestStart == from ? cut(text, from, to) : ELLIPSIS + cut(text, bestStart, to, MAX - ELLIPSIS.length());
    }

    /** Query weight of the hits that a cut starting at {@code start} keeps whole. */
    private static int covered(String text, int start, int to, List<Hit> hits, int windowStart) {
        int budget = MAX - (start > windowStart ? ELLIPSIS.length() : 0) - ELLIPSIS.length();
        int end = codePoints(text, start, to) <= budget ? to : text.offsetByCodePoints(start, budget);
        Set<String> seen = new HashSet<>();
        int weight = 0;
        for (Hit hit : hits) {
            if (hit.start() >= start && hit.end() <= end && seen.add(hit.key())) weight += hit.weight();
        }
        return weight;
    }

    private static int wordStart(String text, int floor, int position) {
        int space = text.lastIndexOf(' ', position - 1);
        return Math.max(floor, space + 1);
    }

    private static String cut(String text, int from, int to) {
        return cut(text, from, to, MAX);
    }

    /**
     * {@code text[from, to)} in at most {@code limit} code points with a trailing ellipsis when cut. The cut goes back
     * to the last space unless that would drop more than half of the kept text (one very long word); it never splits a
     * surrogate pair.
     */
    private static String cut(String text, int from, int to, int limit) {
        if (codePoints(text, from, to) <= limit) return text.substring(from, to);
        int end = text.offsetByCodePoints(from, limit - ELLIPSIS.length());
        if (text.charAt(end) != ' ') {
            int space = text.lastIndexOf(' ', end - 1);
            if (space > from + (end - from) / 2) end = space;
        }
        return text.substring(from, end).stripTrailing() + ELLIPSIS;
    }

    private static List<Hit> hits(String text, int from, int to, Map<String, Integer> query) {
        List<Hit> hits = new ArrayList<>();
        if (to <= from) return hits;
        Matcher token = TOKEN.matcher(text).region(from, to);
        while (token.find()) {
            String key = key(token.group());
            Integer weight = key == null ? null : query.get(key);
            if (weight != null) hits.add(new Hit(token.start(), token.end(), key, weight));
        }
        return hits;
    }

    /** The distinct terms of a text with their weights. */
    static Map<String, Integer> terms(String text) {
        Map<String, Integer> terms = new HashMap<>();
        Matcher token = TOKEN.matcher(text);
        while (token.find()) {
            String key = key(token.group());
            if (key != null) terms.put(key, key.startsWith("#") ? NUMBER_WEIGHT : WORD_WEIGHT);
        }
        return terms;
    }

    /**
     * The matching key of a token, or null when it carries no evidence. Numbers keep only their digits, so "25.000" and
     * "25000" match. Words are folded; a word of at least {@link #STEM} letters keeps its first {@link #STEM}, so
     * "çalışanlara" matches "Çalışanın" and "İzinleri" matches "izinlerin". Shorter words must be equal.
     */
    static String key(String token) {
        if (Character.isDigit(token.codePointAt(0))) {
            StringBuilder digits = new StringBuilder("#");
            token.codePoints().filter(Character::isDigit).forEach(cp -> digits.append(Character.digit(cp, 10)));
            return digits.toString();
        }
        String word = fold(token);
        if (word.length() < 2 || STOP_WORDS.contains(word)) return null;
        return word.length() >= STEM ? word.substring(0, STEM) : word;
    }

    /**
     * Lower case with the Turkish locale ("İ" → "i", "I" → "ı"), then without diacritics and with dotless i folded, so
     * "İZİN", "izin" and "ızın" (a Turkish keyboard missing, or an English one) are the same word: ş→s, ğ→g, ç→c,
     * ö→o, ü→u, ı→i, â→a.
     */
    static String fold(String word) {
        String lower = Normalizer.normalize(word, Normalizer.Form.NFKC).toLowerCase(TURKISH).replace('ı', 'i');
        return MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
    }

    private static int codePoints(String text, int from, int to) {
        return text.codePointCount(from, to);
    }
}
