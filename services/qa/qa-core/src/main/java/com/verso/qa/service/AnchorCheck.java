package com.verso.qa.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lexical groundedness check of one answer sentence (ADR-0047): every number, number word and code the sentence
 * states must stand in a cited passage, next to one of the words it stands next to in the sentence. "Doğum izni on dört
 * haftadır" against a passage that says "on altı hafta" fails; so does "kayıt P1 açılır" when the passage has P1 only
 * in another rule. A number the asker wrote in the question ("30.000 TL'lik bir masraf …") is allowed anywhere.
 *
 * <p>Deliberately one-sided: the sentence side reads only what is surely a quantity, the passage side reads generously
 * (a missed passage number could only raise a false flag, a missed sentence number only skips a check). A sentence with
 * no such anchor is not checked at all. The check cannot see negation, a swapped name or a paraphrase; it finds wrong
 * quantities and codes, which is where a small model's answers go wrong in practice (ADR-0017, ADR-0020) and where a
 * wrong answer costs most. Pure and allocation-light: it runs on the hot path after the chat call, a fraction of a
 * millisecond per sentence. Measured in docs/evidence/dayanaklilik-olcumu.md; the prototype there is this code.
 */
final class AnchorCheck {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    // A code is a token of capitals and digits with at least one of each, hyphens inside: P1, SAT-E058, WMS-204,
    // A-03-02-B. Then numbers (dots, commas and colons inside: 25.000, 16.30), then words, then line breaks.
    private static final Pattern TOKEN = Pattern.compile(
            "(?<code>(?<![\\p{L}\\p{N}])(?=[\\p{Lu}\\p{N}-]*\\p{N})(?=[\\p{Lu}\\p{N}-]*\\p{Lu})[\\p{Lu}\\p{N}]+"
                    + "(?:-[\\p{Lu}\\p{N}]+)*(?![\\p{L}\\p{N}]))"
                    + "|(?<number>[0-9]+(?:[.,:][0-9]+)*)"
                    + "|(?<word>\\p{L}[\\p{L}\\p{M}]*)"
                    + "|(?<line>\\n)");

    // Turkish number words. Order matters only for the prefix test; no stem is a prefix of another that also accepts
    // the remainder as a suffix ("altı"/"altmış", "on"/"otuz").
    private static final Map<String, Integer> UNITS = ordered(
            "bir", 1, "iki", 2, "üç", 3, "dört", 4, "dörd", 4, "beş", 5, "altı", 6, "yedi", 7, "sekiz", 8, "dokuz", 9);
    private static final Map<String, Integer> TENS = ordered(
            "on", 10, "yirmi", 20, "otuz", 30, "kırk", 40, "elli", 50, "altmış", 60, "yetmiş", 70, "seksen", 80,
            "doksan", 90);
    private static final Map<String, Integer> SCALES = ordered("yüz", 100, "bin", 1000, "milyon", 1_000_000);
    // English number words, read in passages only (a generous passage side can only spare a flag): companies keep
    // English documents, and the model answers them in Turkish.
    private static final Map<String, Integer> ENGLISH = ordered(
            "one", 1, "two", 2, "three", 3, "four", 4, "five", 5, "six", 6, "seven", 7, "eight", 8, "nine", 9,
            "ten", 10, "eleven", 11, "twelve", 12, "thirteen", 13, "fourteen", 14, "fifteen", 15, "sixteen", 16,
            "seventeen", 17, "eighteen", 18, "nineteen", 19, "twenty", 20, "thirty", 30, "forty", 40, "fifty", 50,
            "sixty", 60, "seventy", 70, "eighty", 80, "ninety", 90);
    private static final Map<String, Integer> ENGLISH_SCALES = ordered("hundred", 100, "thousand", 1000,
            "million", 1_000_000);
    /** Below this many shared describing terms, a sentence and its passages are not comparable word by word. */
    static final int MIN_SHARED_TERMS = 2;
    // Also ordinary words: "bir kayıt" (an article), "onu" (him), "yüzde", "binde", "altında", "yedi" (ate). In a
    // sentence they count only bare inside a compound ("yirmi bir") or as an ordinal ("onuncu").
    private static final Set<String> AMBIGUOUS = Set.of("bir", "on", "yüz", "bin", "altı", "yedi");
    // Case, ordinal and distributive endings, after a vowel and after a consonant; nothing else makes a number word.
    private static final Pattern AFTER_VOWEL =
            Pattern.compile("nc[ıiuü](?:s[ıiuü])?|y[ae]|d[ae]n?|n[ıiuü]n|s[ıiuü]|y[ıiuü]|ş[ae]r|l[ae]r");
    private static final Pattern AFTER_CONSONANT =
            Pattern.compile("[ıiuü]nc[ıiuü](?:s[ıiuü])?|[ae]|[dt][ae]n?|[ıiuü]n|[ıiuü]|ş[ae]r|l[ae]r");
    private static final Pattern ORDINAL = Pattern.compile("[ıiuü]?nc[ıiuü](?:s[ıiuü])?");
    private static final String VOWELS = "aeıioöuü";

    // Folded to the first five letters like ExcerptSelector's keys; these words describe nothing about a quantity.
    private static final Set<String> STOP_WORDS = Set.of(
            "ve", "ile", "veya", "ya", "da", "de", "en", "bu", "bir", "icin", "olan", "olara", "gibi", "her", "kadar",
            "fazla", "az", "gec");
    private static final int STEM = 5;
    /** How many words on each side describe an anchor in the sentence, and in a passage (measured: 2 and 3). */
    static final int SENTENCE_CONTEXT = 2;
    static final int PASSAGE_CONTEXT = 3;

    /** A token: an anchor ("N:14", "C:SAT-E058"), a describing term (folded, five letters), or a line break. */
    record Token(String anchor, String term, boolean lineBreak) {
    }

    private AnchorCheck() {
    }

    /**
     * The anchors of {@code sentence} that no cited passage supports, in order; empty when every anchor is supported
     * or the sentence has none. {@code checked} of the result says how many anchors there were.
     */
    static Result check(String sentence, List<String> passages, String question) {
        List<Token> own = tokens(sentence, true);
        List<List<Token>> sources = new ArrayList<>(passages.size());
        for (String passage : passages) sources.add(tokens(passage, false));
        Set<String> asked = new HashSet<>();
        for (Token token : tokens(question == null ? "" : question, false)) {
            if (token.anchor() != null) asked.add(token.anchor());
        }
        // Neighbours can only be compared when the sentence speaks the passage's language: a Turkish answer about an
        // English passage ("yirmi iş günü" / "twenty working days") shares no words with it, and then the quantity
        // only has to be there.
        boolean local = sharedTerms(own, sources) >= MIN_SHARED_TERMS;
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (int k = 0; k < own.size(); k++) {
            Token token = own.get(k);
            if (token.anchor() == null) continue;
            checked++;
            Set<String> context = local ? neighbours(own, k, SENTENCE_CONTEXT) : Set.of();
            if (!supported(token.anchor(), context, sources, asked)) missing.add(token.anchor());
        }
        return new Result(checked, List.copyOf(missing));
    }

    /** How many describing terms of the sentence any passage also uses. */
    private static int sharedTerms(List<Token> own, List<List<Token>> sources) {
        Set<String> theirs = new HashSet<>();
        for (List<Token> source : sources) {
            for (Token token : source) if (token.term() != null) theirs.add(token.term());
        }
        Set<String> mine = new HashSet<>();
        for (Token token : own) if (token.term() != null) mine.add(token.term());
        int shared = 0;
        for (String term : mine) if (meets(Set.of(term), theirs)) shared++;
        return shared;
    }

    /** {@code checked} anchors in the sentence; {@code missing} the unsupported ones. */
    record Result(int checked, List<String> missing) {
        boolean unverified() {
            return !missing.isEmpty();
        }
    }

    private static boolean supported(String anchor, Set<String> context, List<List<Token>> sources, Set<String> asked) {
        if (asked.contains(anchor)) return true;
        for (List<Token> source : sources) {
            for (int m = 0; m < source.size(); m++) {
                if (!anchor.equals(source.get(m).anchor())) continue;
                // A bare number with no word around it ("14.") has nothing to compare: being there is enough.
                if (context.isEmpty() || meets(context, neighbours(source, m, PASSAGE_CONTEXT))) return true;
                // A code is a table key: the rest of its row describes it ("WMS-502 | ERP bağlantısı yok | …").
                if (anchor.startsWith("C:") && meets(context, lineTerms(source, m))) return true;
            }
        }
        return false;
    }

    static List<Token> tokens(String text, boolean strict) {
        List<String> raw = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        while (matcher.find()) {
            String kind = matcher.group("code") != null ? "code" : matcher.group("number") != null ? "number"
                    : matcher.group("word") != null ? "word" : "line";
            raw.add(matcher.group());
            kinds.add(kind);
        }
        List<Token> out = new ArrayList<>(raw.size());
        int i = 0;
        while (i < raw.size()) {
            String kind = kinds.get(i);
            String token = raw.get(i);
            switch (kind) {
                case "line" -> {
                    out.add(new Token(null, null, true));
                    i++;
                    continue;
                }
                case "code" -> {
                    out.add(new Token("C:" + token.toUpperCase(TURKISH), null, false));
                    i++;
                    continue;
                }
                case "number" -> {
                    out.add(new Token("N:" + digits(token), null, false));
                    i++;
                    continue;
                }
                default -> {
                }
            }
            // Number words: "on dört", "yirmi altı", "beş yüz", "on beşinci"; an ending closes the number.
            long total = 0;
            long current = 0;
            int parts = 0;
            Value first = null;
            int j = i;
            while (j < raw.size() && kinds.get(j).equals("word")) {
                Value value = value(lower(raw.get(j)), strict);
                if (value == null) break;
                if (parts++ == 0) first = value;
                if (value.scale()) {
                    if (value.value() == 100) {
                        current = (current == 0 ? 1 : current) * 100;
                    } else {
                        total += (current == 0 ? 1 : current) * value.value();
                        current = 0;
                    }
                } else {
                    current += value.value();
                }
                j++;
                if (!value.bare()) break;
            }
            if (parts > 0) {
                boolean lone = parts == 1 && AMBIGUOUS.contains(first.stem()) && first.bare();
                if (!(strict && lone)) {
                    // A lone "altı" in a passage may be "below" ("3 ve altı puan"): it also stays a describing word,
                    // and takes a neighbour's place even when it is "bir" (as measured).
                    out.add(new Token("N:" + (total + current), lone ? prefix(token) : null, false));
                    i = j;
                    continue;
                }
            }
            out.add(new Token(null, term(token), false));
            i++;
        }
        return out;
    }

    private record Value(int value, String stem, boolean bare, boolean scale) {
    }

    /**
     * The value of a number word, or null. {@code strict}: an ambiguous stem counts only bare (a lone bare one is
     * dropped by the caller unless it is part of a compound) or as an ordinal.
     */
    private static Value value(String word, boolean strict) {
        for (Map<String, Integer> table : List.of(UNITS, TENS, SCALES)) {
            for (Map.Entry<String, Integer> entry : table.entrySet()) {
                String stem = entry.getKey();
                if (!word.startsWith(stem)) continue;
                String rest = word.substring(stem.length());
                if (rest.isEmpty()) return new Value(entry.getValue(), stem, true, table == SCALES);
                boolean vowel = VOWELS.indexOf(stem.charAt(stem.length() - 1)) >= 0;
                if (!(vowel ? AFTER_VOWEL : AFTER_CONSONANT).matcher(rest).matches()) continue;
                if (strict && AMBIGUOUS.contains(stem) && !ORDINAL.matcher(rest).matches()) continue;
                return new Value(entry.getValue(), stem, false, table == SCALES);
            }
        }
        if (!strict) {
            Integer english = ENGLISH.get(word);
            if (english != null) return new Value(english, word, true, false);
            Integer scale = ENGLISH_SCALES.get(word);
            if (scale != null) return new Value(scale, word, true, true);
        }
        return null;
    }

    /** The nearest {@code count} describing terms on each side of {@code at}, across line breaks. */
    private static Set<String> neighbours(List<Token> tokens, int at, int count) {
        Set<String> terms = new HashSet<>();
        for (int direction : new int[] {-1, 1}) {
            int found = 0;
            for (int m = at + direction; m >= 0 && m < tokens.size() && found < count; m += direction) {
                String term = tokens.get(m).term();
                if (term != null) {
                    terms.add(term);
                    found++;
                }
            }
        }
        return terms;
    }

    private static Set<String> lineTerms(List<Token> tokens, int at) {
        Set<String> terms = new HashSet<>();
        for (int direction : new int[] {-1, 1}) {
            for (int m = at + direction; m >= 0 && m < tokens.size() && !tokens.get(m).lineBreak(); m += direction) {
                if (tokens.get(m).term() != null) terms.add(tokens.get(m).term());
            }
        }
        return terms;
    }

    /** Two terms describe the same thing when equal, or when the shorter (3+ letters) starts the longer: saat/saattir. */
    private static boolean meets(Set<String> own, Set<String> other) {
        for (String a : own) {
            for (String b : other) {
                String shorter = a.length() <= b.length() ? a : b;
                String longer = shorter == a ? b : a;
                if (shorter.equals(longer) || (shorter.length() >= 3 && longer.startsWith(shorter))) return true;
            }
        }
        return false;
    }

    private static String term(String word) {
        String key = prefix(word);
        return key.length() < 2 || STOP_WORDS.contains(key) ? null : key;
    }

    private static String prefix(String word) {
        String folded = ExcerptSelector.fold(word);
        return folded.length() > STEM ? folded.substring(0, STEM) : folded;
    }

    /** Only the digits: 25.000 = 25000, 16.30 = 1630, leading zeros dropped (02.00 = 200), like ADR-0020. */
    private static String digits(String number) {
        return number.replaceAll("[^0-9]", "").replaceFirst("^0+(?=[0-9])", "");
    }

    private static String lower(String word) {
        return word.toLowerCase(TURKISH);
    }

    private static Map<String, Integer> ordered(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], (Integer) pairs[i + 1]);
        return map;
    }
}
