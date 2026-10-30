package com.verso.qa.service;

import com.verso.platform.core.text.TurkishText;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The key facts of a question (ADR-0060): the values that make two otherwise identical questions different questions.
 * Cosine similarity cannot see them ("500.000 TL üstü alım" and "50.000 TL üstü alım" embed almost alike), so a verified
 * card may answer a question only when every key fact of the question is also a key fact of the card's question.
 *
 * <p>Read: numbers in Turkish notation ("500.000", "1,5", "500 bin", number words "on beş"), percentages ("%18",
 * "yüzde 18"), amounts with a currency ("50.000 TL", "₺500", "2 milyon lira"), quantities with a measuring unit
 * ("30 gün", "10 iş günü", "8 GB"), dates, months, years and times, codes and references ("PRC-12", "P1", "madde 7",
 * "7. madde", "7/2"), double-quoted terms, explicit negation ("değil", "olmayan", "hariç"; the verb suffix
 * "-me/-ma" is deliberately not read: it is everywhere in ordinary questions), and the direction of a comparison
 * ("fazla", "üzeri", "aşan"; "az", "düşük"; "en az", "en çok"): "5 yıldan az" and "5 yıldan fazla" differ in that
 * word alone. "altı" (also "six"), "altında" (also "under") and "çok" alone are not read.
 *
 * <p>Deterministic, pure and allocation-light: it runs on the question's hot path, once per question and once per
 * candidate card, without any model or I/O. Neither the text nor a fact is ever logged; only the {@link Kind} of a
 * conflict leaves this class, as a reason code (llm-rules 2.1).
 */
public final class KeyFacts {

    /** What a key fact is; the kind of a conflict is the rejection reason of a card (a fixed value, logged). */
    public enum Kind { NUMBER, AMOUNT, PERCENT, QUANTITY, DATE, CODE, QUOTE, NEGATION, COMPARATOR }

    /**
     * One fact. Numeric kinds carry the normalised value ("500000", "1.5") and a qualifier: the currency of an amount,
     * the unit of a quantity, empty otherwise. Dates carry a granularity prefix ("y2025", "m03", "ym2025-03").
     */
    record Fact(Kind kind, String value, String qualifier) {

        Fact(Kind kind, String value) {
            this(kind, value, "");
        }

        boolean numeric() {
            return kind == Kind.NUMBER || kind == Kind.AMOUNT || kind == Kind.PERCENT || kind == Kind.QUANTITY;
        }
    }

    private static final Pattern QUOTED = Pattern.compile("[\"“”«»„]([^\"“”«»„\\n]{1,80})[\"“”«»„]");
    // In order: full dates, times, codes (capitals and digits, the groundedness check's own definition), slash
    // references ("7/2", "24/7"), numbers, and the single characters and words around them.
    private static final Pattern TOKEN = Pattern.compile(
            "(?<date>(?<![\\p{L}\\p{N}])(?:\\d{1,2}[./]\\d{1,2}[./]\\d{4}|\\d{4}-\\d{2}-\\d{2})(?![\\p{N}]))"
                    + "|(?<time>(?<![\\p{N}])\\d{1,2}:\\d{2}(?![\\p{N}]))"
                    + "|(?<code>" + TurkishText.CODE_REGEX + ")"
                    + "|(?<ref>(?<![\\p{N}])\\d{1,4}/\\d{1,4}(?![\\p{N}/]))"
                    + "|(?<number>\\d+(?:[.,]\\d+)*)"
                    + "|(?<sign>[%₺$€£])"
                    + "|(?<word>\\p{L}[\\p{L}\\p{M}]*)");
    private static final Pattern GLUED_UNIT = Pattern.compile("(\\d+(?:[.,]\\d+)?)(\\p{Lu}+)");

    // Number words, matched on the Turkish lower case with their diacritics; folded (no diacritics) they still count,
    // but as ambiguous ones: "ucu" may be "the tip", "on" may be "ön" (front).
    private static final Map<String, Integer> NUMBER_WORDS = ordered(
            "bir", 1, "iki", 2, "üç", 3, "dört", 4, "dörd", 4, "beş", 5, "altı", 6, "yedi", 7, "sekiz", 8, "dokuz", 9,
            "on", 10, "yirmi", 20, "otuz", 30, "kırk", 40, "elli", 50, "altmış", 60, "yetmiş", 70, "seksen", 80,
            "doksan", 90);
    private static final Map<String, Long> SCALES = Map.of("yüz", 100L, "bin", 1_000L, "milyon", 1_000_000L,
            "milyar", 1_000_000_000L);
    // Also ordinary words ("bir" is an article, "yüz" a face, "altı" below, "yedi" ate, "bin" ride): alone, they count
    // only when a unit, a currency or a scale follows ("bin TL", "bir hafta").
    private static final Set<String> AMBIGUOUS = Set.of("bir", "on", "yüz", "bin", "altı", "yedi");
    // Case, ordinal and derivational endings on a number or a number word (folded); nothing else is read as one.
    private static final Set<String> SUFFIXES = Set.of("", "a", "e", "i", "u", "da", "de", "ta", "te", "dan", "den",
            "tan", "ten", "in", "un", "nin", "nun", "si", "su", "ya", "ye", "yi", "yu", "ar", "er", "sar", "ser", "nci",
            "ncu", "inci", "uncu", "lik", "luk", "li", "lu", "ler", "lar", "dir", "dur", "tir", "tur");

    private static final Map<String, String> CURRENCIES = Map.ofEntries(Map.entry("tl", "TRY"),
            Map.entry("try", "TRY"), Map.entry("lira", "TRY"), Map.entry("₺", "TRY"), Map.entry("usd", "USD"),
            Map.entry("dolar", "USD"), Map.entry("$", "USD"), Map.entry("eur", "EUR"), Map.entry("euro", "EUR"),
            Map.entry("avro", "EUR"), Map.entry("€", "EUR"), Map.entry("gbp", "GBP"), Map.entry("sterlin", "GBP"),
            Map.entry("£", "GBP"));
    // Measuring units only: a counted noun ("3 teklif") stays a plain number, so a paraphrase that words the noun
    // differently ("3 ayrı teklif") is not mistaken for a different quantity.
    private static final Map<String, String> UNITS = ordered("saniye", "saniye", "dakika", "dakika", "dk", "dakika",
            "saat", "saat", "gün", "gün", "hafta", "hafta", "ay", "ay", "yıl", "yıl", "sene", "yıl", "kg", "kg",
            "kilo", "kg", "gram", "gram", "ton", "ton", "km", "km", "metre", "metre", "cm", "cm", "mm", "mm",
            "litre", "litre", "lt", "litre", "adet", "adet", "kişi", "kişi", "kb", "KB", "mb", "MB", "gb", "GB",
            "tb", "TB", "mbps", "Mbps", "gbps", "Gbps", "karakter", "karakter", "hane", "hane", "sayfa", "sayfa",
            "kez", "kez", "defa", "kez", "puan", "puan");
    // Units that are also ordinary words or short stems ("ay" is the moon and "ayrıca"): exact or a short ending only.
    private static final Set<String> SHORT_UNITS = Set.of("ay", "dk", "kg", "km", "cm", "mm", "lt", "kb", "mb", "gb",
            "tb", "ton", "kez", "hane");
    private static final Map<String, String> MONTHS = ordered("ocak", "01", "şubat", "02", "mart", "03", "nisan", "04",
            "mayıs", "05", "haziran", "06", "temmuz", "07", "ağustos", "08", "eylül", "09", "ekim", "10", "kasım", "11",
            "aralık", "12");
    // A word followed by a number names an article, a clause, an annex, a level: "madde 7", "Ek 2", "seviye 3".
    private static final Set<String> REFERENCES = Set.of("madde", "md", "fıkra", "bent", "bölüm", "ek", "tablo",
            "form", "sürüm", "versiyon", "seviye", "kademe", "kategori", "sınıf", "adım", "aşama", "faz", "öncelik");
    // Explicit negation and exclusion with the endings each takes ("değildir", "olmayanlar", "hariçtir"), folded;
    // "harici" (external) is not one.
    private static final Pattern NEGATION = Pattern.compile("degil[a-z]{0,5}|olmayan[a-z]{0,5}|haric(?:tir|tur)?");
    // The direction of a comparison, folded: above, below; "en az" / "en çok" / "en fazla" are read as a pair.
    private static final Pattern ABOVE = Pattern.compile("fazla(?:si|sini|siyla)?|ustu(?:nde|ndeki)?|uzeri(?:nde|ndeki)?"
            + "|asan(?:lar|larda)?|askin|buyuk");
    private static final Pattern BELOW = Pattern.compile("az|asagisi|dusuk");
    private static final Pattern MONTH_NAMED = Pattern.compile("ay(?:i|in|inda|indan|ina|inin|da|dan|a)?");
    // References also read backwards ("7. madde", "3'üncü fıkra"), but only where that is the usual order: "3 aşama"
    // is three stages, not stage 3.
    private static final Set<String> REVERSIBLE = Set.of("madde", "md", "fıkra", "bent", "bölüm");

    private final Set<Fact> facts;
    private final String folded;

    private KeyFacts(Set<Fact> facts, String folded) {
        this.facts = facts;
        this.folded = folded;
    }

    /** The key facts of {@code text} (a question). Null or blank: none. */
    public static KeyFacts of(String text) {
        if (text == null || text.isBlank()) return new KeyFacts(Set.of(), "");
        String normal = Normalizer.normalize(text, Normalizer.Form.NFKC);
        Set<Fact> facts = new LinkedHashSet<>();
        StringBuilder rest = new StringBuilder();
        Matcher quoted = QUOTED.matcher(normal);
        int last = 0;
        while (quoted.find()) {
            String term = words(quoted.group(1));
            if (!term.isEmpty()) facts.add(new Fact(Kind.QUOTE, term));
            // The quoted text is read for numbers and codes too: "\"PRC-12\" formu" has a code.
            rest.append(normal, last, quoted.start()).append(' ').append(quoted.group(1)).append(' ');
            last = quoted.end();
        }
        rest.append(normal.substring(last));
        new Reader(tokens(rest.toString()), facts).read();
        return new KeyFacts(Collections.unmodifiableSet(facts), " " + words(normal) + " ");
    }

    /** The facts, for tests; never logged. */
    Set<Fact> facts() {
        return facts;
    }

    /**
     * Why a card whose question has the key facts {@code card} cannot answer a question with these: empty when every
     * fact of this question is a fact of the card's question (a subset, or equal). Negation must agree both ways: a
     * card about what is not covered never answers what is, and the other way round. A comparison conflicts only when
     * both questions state one and the directions differ ("5 yıldan az" / "5 yıldan fazla"): a card that adds "üstü"
     * to the same amount, or a paraphrase that says "itibaren" instead, still matches.
     */
    public Optional<Kind> conflictWith(KeyFacts card) {
        boolean negated = facts.stream().anyMatch(f -> f.kind() == Kind.NEGATION);
        boolean cardNegated = card.facts.stream().anyMatch(f -> f.kind() == Kind.NEGATION);
        if (negated != cardNegated) return Optional.of(Kind.NEGATION);
        Set<String> compared = comparators();
        Set<String> cardCompared = card.comparators();
        if (!compared.isEmpty() && !cardCompared.isEmpty() && !compared.equals(cardCompared)) {
            return Optional.of(Kind.COMPARATOR);
        }
        for (Fact fact : facts) {
            if (fact.kind() != Kind.NEGATION && fact.kind() != Kind.COMPARATOR && !card.covers(fact)) {
                return Optional.of(fact.kind());
            }
        }
        return Optional.empty();
    }

    private Set<String> comparators() {
        return facts.stream().filter(f -> f.kind() == Kind.COMPARATOR).map(Fact::value)
                .collect(java.util.stream.Collectors.toSet());
    }

    private boolean covers(Fact fact) {
        if (facts.contains(fact)) return true;
        return switch (fact.kind()) {
            // A bare number is satisfied by the same value in any form ("3" by "3 gün", "50.000" by "50.000 TL").
            // Also a number the card writes inside a code the question did not read as one ("prc-12" / "PRC-12").
            case NUMBER -> facts.stream().anyMatch(f -> f.numeric() && f.value().equals(fact.value()))
                    || folded.contains(" " + fact.value() + " ");
            // A qualified one only by itself or by the same bare value: "30 gün" never by "30 saat", "50.000 TL"
            // never by "50.000 USD".
            case AMOUNT, PERCENT, QUANTITY -> facts.contains(new Fact(Kind.NUMBER, fact.value()));
            // Written without quotes or in lower case on the card: the same words are enough.
            case QUOTE -> folded.contains(" " + fact.value() + " ");
            case CODE -> folded.contains(" " + TurkishText.fold(fact.value()).replace('-', ' ') + " ");
            case DATE, NEGATION, COMPARATOR -> false;
        };
    }

    // ------------------------------------------------------------------------------------------------- reading

    private record Token(String kind, String text, String lower, String folded) {
    }

    private static List<Token> tokens(String text) {
        List<Token> out = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String kind = matcher.group("date") != null ? "date" : matcher.group("time") != null ? "time"
                    : matcher.group("code") != null ? "code" : matcher.group("ref") != null ? "ref"
                    : matcher.group("number") != null ? "number" : matcher.group("sign") != null ? "sign" : "word";
            String raw = matcher.group();
            if (kind.equals("code")) {
                // "30TL", "8GB": a number glued to its currency or unit, not an identifier.
                Matcher glued = GLUED_UNIT.matcher(raw);
                if (glued.matches()) {
                    String suffix = glued.group(2).toLowerCase(TurkishText.TURKISH);
                    if (CURRENCIES.containsKey(suffix) || UNITS.containsKey(suffix)) {
                        out.add(token("number", glued.group(1)));
                        out.add(token("word", glued.group(2)));
                        continue;
                    }
                }
            }
            out.add(token(kind, raw));
        }
        return out;
    }

    private static Token token(String kind, String raw) {
        return new Token(kind, raw, raw.toLowerCase(TurkishText.TURKISH), TurkishText.fold(raw));
    }

    /** One pass over the tokens; each token is consumed by at most one fact. */
    private static final class Reader {

        private final List<Token> tokens;
        private final Set<Fact> facts;
        private final boolean[] used;

        Reader(List<Token> tokens, Set<Fact> facts) {
            this.tokens = tokens;
            this.facts = facts;
            this.used = new boolean[tokens.size()];
        }

        void read() {
            readDatesAndReferences();
            for (int i = 0; i < tokens.size(); i++) {
                if (used[i]) continue;
                Token token = tokens.get(i);
                switch (token.kind()) {
                    case "code" -> {
                        facts.add(new Fact(Kind.CODE, TurkishText.foldUpper(token.text())));
                        used[i] = true;
                    }
                    case "ref" -> {
                        facts.add(new Fact(Kind.CODE, token.text()));
                        used[i] = true;
                    }
                    case "number" -> readNumber(i, parse(token.text()), false, false);
                    case "word" -> {
                        if (negation(token)) {
                            facts.add(new Fact(Kind.NEGATION, ""));
                            used[i] = true;
                        } else if (comparator(i)) {
                            used[i] = true;
                        } else {
                            readNumberWords(i);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        /** Dates, times, months next to a day or year, and references ("madde 7", "7. madde"), before plain numbers. */
        private void readDatesAndReferences() {
            for (int i = 0; i < tokens.size(); i++) {
                Token token = tokens.get(i);
                switch (token.kind()) {
                    case "date" -> {
                        String[] parts = token.text().split("[./-]");
                        boolean iso = parts[0].length() == 4;
                        int year = Integer.parseInt(iso ? parts[0] : parts[2]);
                        int month = Integer.parseInt(parts[1]);
                        int day = Integer.parseInt(iso ? parts[2] : parts[0]);
                        if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                            date(year, String.format("%02d", month), day);
                            used[i] = true;
                        }
                    }
                    case "time" -> {
                        facts.add(new Fact(Kind.DATE, "t" + token.text().replaceFirst("^(\\d):", "0$1:")));
                        used[i] = true;
                    }
                    case "word" -> {
                        String month = stem(token, MONTHS);
                        if (month != null) readMonth(i, MONTHS.get(month));
                        else if (stem(token, REFERENCES) != null) readReference(i);
                    }
                    default -> {
                    }
                }
            }
        }

        private void readMonth(int at, String month) {
            Integer day = null;
            Integer year = null;
            int before = at - 1;
            if (before >= 0 && !used[before] && tokens.get(before).kind().equals("number")) {
                BigDecimal value = parse(tokens.get(before).text());
                if (value != null && between(value, 1, 31)) {
                    day = value.intValue();
                }
            }
            int after = at + 1;
            if (after < tokens.size() && tokens.get(after).kind().equals("number")) {
                BigDecimal value = parse(tokens.get(after).text());
                if (value != null && between(value, 1900, 2100)) {
                    year = value.intValue();
                }
            }
            // "aralık" is also "range", "mart" a stem of other words: only a day, a year or "ayı" makes it a month.
            boolean named = after < tokens.size() && MONTH_NAMED.matcher(tokens.get(after).folded()).matches();
            if (day == null && year == null && !named) return;
            used[at] = true;
            if (day != null) used[before] = true;
            if (year != null) used[after] = true;
            if (year == null) {
                facts.add(new Fact(Kind.DATE, "m" + month));
                if (day != null) facts.add(new Fact(Kind.DATE, "md" + month + "-" + String.format("%02d", day)));
            } else {
                date(year, month, day);
            }
        }

        private void date(int year, String month, Integer day) {
            facts.add(new Fact(Kind.DATE, "y" + year));
            facts.add(new Fact(Kind.DATE, "m" + month));
            facts.add(new Fact(Kind.DATE, "ym" + year + "-" + month));
            if (day != null) {
                String dd = String.format("%02d", day);
                facts.add(new Fact(Kind.DATE, "md" + month + "-" + dd));
                facts.add(new Fact(Kind.DATE, "ymd" + year + "-" + month + "-" + dd));
            }
        }

        /** "madde 7", "madde 7/2", and the reversed "7. madde", "7'nci madde". */
        private void readReference(int at) {
            String reference = stem(tokens.get(at), REFERENCES);
            String name = TurkishText.foldUpper(reference);
            int after = at + 1;
            if (after < tokens.size() && !used[after]
                    && (tokens.get(after).kind().equals("number") || tokens.get(after).kind().equals("ref"))) {
                facts.add(new Fact(Kind.CODE, name + " " + reference(tokens.get(after).text())));
                used[at] = true;
                used[after] = true;
                return;
            }
            if (!REVERSIBLE.contains(reference)) return;
            int before = at - 1;
            if (before >= 0 && !used[before] && ordinalSuffix(tokens.get(before))) before--;
            if (before >= 0 && !used[before] && tokens.get(before).kind().equals("number")) {
                facts.add(new Fact(Kind.CODE, name + " " + reference(tokens.get(before).text())));
                for (int k = before; k <= at; k++) used[k] = true;
            }
        }

        /** A number, then an optional scale ("bin"), then a currency, a unit or a percent sign around it. */
        private void readNumber(int at, BigDecimal value, boolean ambiguous, boolean article) {
            used[at] = true;
            if (value == null) return;
            int next = skipSuffix(at + 1);
            long scale = next < tokens.size() ? scale(tokens.get(next)) : 0;
            if (scale > 0) {
                value = value.multiply(BigDecimal.valueOf(scale));
                used[next] = true;
                next = skipSuffix(next + 1);
                ambiguous = false;
                article = false;
            }
            // A lone "bir" is the article even before a unit ("bir günlük", "biri yılda"): only "bir milyon" counts.
            if (article) return;
            String currency = null;
            String unit = null;
            if (next < tokens.size()) {
                currency = currency(tokens.get(next));
                if (currency != null) used[next] = true;
                else unit = unit(next);
            }
            int before = at - 1;
            boolean percent = before >= 0 && (tokens.get(before).text().equals("%")
                    || tokens.get(before).folded().equals("yuzde"));
            if (currency == null && before >= 0 && tokens.get(before).kind().equals("sign")) {
                currency = CURRENCIES.get(tokens.get(before).text());
            }
            if (ambiguous && currency == null && unit == null && !percent) return;
            String plain = plain(value);
            boolean year = between(value, 1900, 2100) && scale == 0;
            if (year && "yıl".equals(unit) && currency == null && !percent) {
                // "2025 yılında" is the year 2025, not 2025 years.
                facts.add(new Fact(Kind.DATE, "y" + value.intValue()));
            } else if (percent) {
                facts.add(new Fact(Kind.PERCENT, plain));
            } else if (currency != null) {
                facts.add(new Fact(Kind.AMOUNT, plain, currency));
            } else if (unit != null) {
                facts.add(new Fact(Kind.QUANTITY, plain, unit));
            } else if (year) {
                facts.add(new Fact(Kind.DATE, "y" + value.intValue()));
            } else {
                facts.add(new Fact(Kind.NUMBER, plain));
            }
        }

        /** The direction of a comparison at {@code at}: "en az" (min), "en çok"/"en fazla" (max), above, below. */
        private boolean comparator(int at) {
            String word = tokens.get(at).folded();
            int next = at + 1;
            if (word.equals("en") && next < tokens.size() && tokens.get(next).kind().equals("word")) {
                String bound = tokens.get(next).folded();
                if (bound.equals("az") || bound.equals("cok") || bound.equals("fazla")) {
                    facts.add(new Fact(Kind.COMPARATOR, bound.equals("az") ? "min" : "max"));
                    used[next] = true;
                    return true;
                }
            }
            if (ABOVE.matcher(word).matches()) {
                facts.add(new Fact(Kind.COMPARATOR, "above"));
                return true;
            }
            if (BELOW.matcher(word).matches()) {
                facts.add(new Fact(Kind.COMPARATOR, "below"));
                return true;
            }
            return false;
        }

        /** "on beş", "beş yüz", "iki buçuk": a run of number words is one value. */
        private void readNumberWords(int at) {
            BigDecimal total = BigDecimal.ZERO;
            long current = 0;
            int parts = 0;
            boolean ambiguous = true;
            boolean half = false;
            int j = at;
            while (j < tokens.size() && tokens.get(j).kind().equals("word") && !used[j]) {
                Token word = tokens.get(j);
                if (parts > 0 && word.folded().startsWith("bucuk")
                        && SUFFIXES.contains(word.folded().substring(5))) {
                    half = true;
                    j++;
                    break;
                }
                Integer unit = numberWord(word);
                // A scale ("yüz", "bin") ends the run: readNumber multiplies by it.
                if (unit == null) break;
                current += unit;
                if (!AMBIGUOUS.contains(stemOf(word)) && exact(word)) ambiguous = false;
                parts++;
                j++;
                if (!bare(word)) break;
            }
            if (parts == 0) return;
            boolean article = parts == 1 && !half && "bir".equals(stemOf(tokens.get(at)));
            if (parts > 1 || half) ambiguous = false;
            total = total.add(BigDecimal.valueOf(current));
            if (half) total = total.add(new BigDecimal("0.5"));
            for (int k = at; k < j - 1; k++) used[k] = true;
            // The scale ("yüz", "bin") and what follows are read by readNumber from the last word of the run.
            readNumber(j - 1, total, ambiguous, article);
        }

        private int skipSuffix(int at) {
            while (at < tokens.size() && tokens.get(at).kind().equals("word")
                    && !tokens.get(at).folded().isEmpty() && SUFFIXES.contains(tokens.get(at).folded())
                    && tokens.get(at).folded().length() <= 4) {
                used[at] = true;
                at++;
            }
            return at;
        }

        private boolean ordinalSuffix(Token token) {
            return token.kind().equals("word") && Set.of("nci", "ncu", "inci", "uncu").contains(token.folded());
        }

        private String unit(int at) {
            Token token = tokens.get(at);
            // "10 iş günü" is not "10 gün".
            if (token.folded().equals("is") && at + 1 < tokens.size()) {
                String next = stem(tokens.get(at + 1), Map.of("gün", "gün"));
                if (next != null) {
                    used[at] = true;
                    used[at + 1] = true;
                    return "iş günü";
                }
            }
            String unit = stem(token, UNITS);
            if (unit == null) return null;
            used[at] = true;
            return UNITS.get(unit);
        }
    }

    // ------------------------------------------------------------------------------------------------- words

    private static boolean negation(Token word) {
        return NEGATION.matcher(word.folded()).matches();
    }

    /** The currency a word or sign names ("TL", "liralık", "₺"), or null. */
    private static String currency(Token token) {
        if (token.kind().equals("sign")) return CURRENCIES.get(token.text());
        String stem = stem(token, CURRENCIES.keySet());
        return stem == null ? null : CURRENCIES.get(stem);
    }

    /** The key of {@code table} that starts {@code word} with only an ending after it, or null. */
    private static String stem(Token word, Map<String, String> table) {
        return stem(word, table.keySet());
    }

    private static String stem(Token word, Set<String> stems) {
        if (!word.kind().equals("word")) return null;
        for (String stem : stems) {
            String folded = TurkishText.fold(stem);
            if (!word.folded().startsWith(folded)) continue;
            String rest = word.folded().substring(folded.length());
            if (rest.isEmpty()) return stem;
            boolean shortStem = folded.length() <= 3 || SHORT_UNITS.contains(stem);
            if (shortStem ? SUFFIXES.contains(rest) && rest.length() <= 3 : rest.length() <= 5 && rest.matches("[a-z]+")) {
                return stem;
            }
        }
        return null;
    }

    private static Integer numberWord(Token word) {
        String stem = stemOf(word);
        return stem == null ? null : NUMBER_WORDS.get(stem);
    }

    /** The number-word stem of {@code word} (folded comparison, ending checked), longest first, or null. */
    private static String stemOf(Token word) {
        String best = null;
        for (String stem : NUMBER_WORDS.keySet()) {
            String folded = TurkishText.fold(stem);
            if (!word.folded().startsWith(folded)) continue;
            if (!SUFFIXES.contains(word.folded().substring(folded.length()))) continue;
            if (best == null || stem.length() > best.length()) best = stem;
        }
        return best;
    }

    /** Written with the stem's own diacritics ("üç", not "uc"). */
    private static boolean exact(Token word) {
        String stem = stemOf(word);
        return stem != null && word.lower().startsWith(stem);
    }

    private static boolean bare(Token word) {
        String stem = stemOf(word);
        return stem != null && word.folded().equals(TurkishText.fold(stem));
    }

    private static long scale(Token word) {
        if (!word.kind().equals("word")) return 0;
        for (Map.Entry<String, Long> entry : SCALES.entrySet()) {
            String folded = TurkishText.fold(entry.getKey());
            if (word.folded().startsWith(folded) && SUFFIXES.contains(word.folded().substring(folded.length()))) {
                return entry.getValue();
            }
        }
        return 0;
    }

    /**
     * A number in Turkish notation: a dot groups thousands, a comma is the decimal mark ("1.500,75"). A dot not
     * followed by exactly three digits is a decimal point ("1.5"), as is a lone comma.
     */
    static BigDecimal parse(String number) {
        String text = number;
        if (text.contains(".") && text.contains(",")) {
            text = text.replace(".", "").replace(',', '.');
        } else if (text.contains(".")) {
            String[] groups = text.split("\\.");
            boolean thousands = true;
            for (int k = 1; k < groups.length; k++) thousands &= groups[k].length() == 3;
            text = thousands ? text.replace(".", "") : text;
            if (!thousands && groups.length > 2) return null;
        } else if (text.contains(",")) {
            String[] groups = text.split(",");
            if (groups.length > 2) {
                for (int k = 1; k < groups.length; k++) if (groups[k].length() != 3) return null;
                text = text.replace(",", "");
            } else {
                text = text.replace(',', '.');
            }
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String reference(String number) {
        BigDecimal value = number.contains("/") ? null : parse(number);
        return value == null ? number : plain(value);
    }

    private static boolean integer(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }

    /** An integer in [low, high], compared exactly: intValue() would wrap a long digit run into the range. */
    private static boolean between(BigDecimal value, int low, int high) {
        return integer(value) && value.compareTo(BigDecimal.valueOf(low)) >= 0
                && value.compareTo(BigDecimal.valueOf(high)) <= 0;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** Folded words separated by single spaces: the form quoted terms and codes are compared in. */
    private static String words(String text) {
        return String.join(" ", TurkishText.fold(text).split("[^\\p{L}\\p{N}]+")).strip();
    }

    private static <V> Map<String, V> ordered(Object... pairs) {
        Map<String, V> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked") V value = (V) pairs[i + 1];
            map.put((String) pairs[i], value);
        }
        return map;
    }

    @Override
    public String toString() {
        return "KeyFacts[count=" + facts.size() + "]";
    }
}
