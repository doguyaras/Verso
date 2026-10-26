package com.verso.document.systemmodel;

import com.verso.platform.core.text.TurkishText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The privacy rule of the system model (ADR-0054, KVKK, works councils): an owner is a job title or a department, never
 * a person. An owner phrase ("Depo Sorumlusu Ahmet Yılmaz tarafından") is reduced to the title ("Depo Sorumlusu"); a
 * phrase without a title word ("Ahmet Bey sorumludur") is dropped. The title is the last title word with at most two
 * words before it; anything after it is cut (that is where a name follows a title), and a given name, an honorific or a
 * function word stops the words before it, together with the surname right after a given name.
 *
 * <p>Deterministic and closed: a title word must come from {@link #HEADS}, a name is recognised from {@link #GIVEN_NAMES}
 * and the honorifics. Known limit (ADR-0054): an unlisted given name directly in front of a title can survive as one of
 * the two words before it; the list is extended from real documents, and person entities do not exist at all.
 */
public final class RoleNames {

    /** Title and department words, folded; a word counts when it starts with one of these stems. */
    static final List<String> HEADS = List.of(
            "mudur", "sorumlu", "sefi", "sefligi", "uzman", "yonetici", "direktor", "departman", "birim", "ekib",
            "ekip", "takim", "operator", "amir", "koordinator", "lider", "baskan", "personel", "yetkili", "temsilci",
            "asistan", "analist", "planlamaci", "denetci", "muhendis", "teknisyen", "kasiyer", "depocu", "satinalmaci",
            "muhasebeci", "bolum", "kurul", "komite", "manager", "team", "department", "officer", "owner", "supervisor",
            "lead", "head", "specialist", "administrator", "admin");
    /** Heads that say nothing alone ("Sorumlu: Ekip" is not an owner). */
    private static final Set<String> GENERIC = Set.of("sorumlu", "sorumlusu", "yetkili", "yetkilisi", "personel",
            "personeli", "ekip", "ekibi", "takim", "takimi", "birim", "birimi", "owner", "lead", "head", "team",
            "admin");
    private static final Set<String> HONORIFICS = Set.of("bey", "hanim", "sayin", "sn", "dr", "av", "prof", "doc",
            "mr", "mrs", "ms", "uzm", "op", "muh");
    private static final Set<String> STOPS = Set.of("ve", "ile", "veya", "bu", "su", "o", "her", "tum", "icin",
            "olarak", "da", "de", "ise", "en", "ilgili", "gore", "kadar", "tarafindan", "the", "and", "of", "by",
            "is", "are");
    /** Common given names, folded (Turkish first, then frequent foreign ones in Turkish companies). */
    static final Set<String> GIVEN_NAMES = Set.of(
            "ahmet", "mehmet", "mustafa", "ali", "huseyin", "hasan", "ibrahim", "ismail", "osman", "yusuf", "murat",
            "omer", "ramazan", "halil", "suleyman", "abdullah", "mahmut", "recep", "salih", "fatih", "kadir", "emre",
            "hakan", "adem", "kemal", "yasar", "bayram", "orhan", "serkan", "burak", "kerem", "can", "cem", "deniz",
            "emin", "eren", "erkan", "ersin", "erdem", "gokhan", "guray", "ilker", "kaan", "koray", "levent", "mert",
            "metin", "onur", "ozan", "selim", "sinan", "tolga", "tuncay", "ugur", "umut", "volkan", "yasin", "yilmaz",
            "baris", "berk", "cagri", "caner", "cengiz", "dogan", "dogu", "engin", "ercan", "erhan", "faruk", "ferhat",
            "gurkan", "harun", "ilhan", "irfan", "kamil", "kenan", "mesut", "necati", "nihat", "oguz", "okan", "ozgur",
            "riza", "sedat", "selcuk", "serdar", "sefa", "sahin", "tahir", "taner", "tarik", "turgut", "vedat", "zafer",
            "ayse", "fatma", "emine", "hatice", "zeynep", "elif", "meryem", "sultan", "zehra", "hulya", "esra", "ozlem",
            "derya", "ebru", "gamze", "gulsen", "hande", "ipek", "melek", "merve", "nur", "nurcan", "pinar", "seda",
            "selin", "sevgi", "sibel", "songul", "tugba", "yasemin", "yesim", "asli", "aylin", "ayten", "bahar",
            "banu", "berna", "betul", "buket", "burcu", "busra", "cansu", "ceren", "didem", "dilek", "duygu", "ece",
            "eda", "ela", "filiz", "funda", "gizem", "gul", "gulay", "irem", "kubra", "leyla", "melike", "nazli",
            "nil", "nilay", "ozge", "rabia", "sema", "serap", "sevda", "seyma", "sinem", "tuba", "tulay", "yeliz",
            "zerrin", "gokce", "basak", "damla", "dilara", "ilknur", "neslihan", "aysel", "gulcan", "nermin",
            "john", "james", "michael", "david", "robert", "william", "thomas", "mary", "anna", "maria", "peter",
            "paul", "mark", "sarah", "laura", "julia", "daniel", "alex", "andreas", "stefan", "klaus",
            "hans", "jurgen", "wolfgang", "sabine", "petra", "monika");
    private static final Pattern PARENS = Pattern.compile("\\(([^()]{2,80})\\)");
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}\\p{M}.'’]*");
    private static final int MAX_BEFORE = 2;

    private RoleNames() {
    }

    /** The title of an owner phrase, or empty when it names no title (then the phrase is dropped). */
    public static Optional<String> title(String phrase) {
        if (phrase == null || phrase.isBlank()) return Optional.empty();
        Matcher inner = PARENS.matcher(phrase);
        while (inner.find()) {
            Optional<String> fromParens = fromWords(words(inner.group(1)));
            if (fromParens.isPresent()) return fromParens;
        }
        return fromWords(words(PARENS.matcher(phrase).replaceAll(" ")));
    }

    /** True when the folded word is a listed given name or an honorific: never part of a stored role. */
    static boolean personWord(String word) {
        String folded = bare(word);
        return GIVEN_NAMES.contains(folded) || HONORIFICS.contains(folded);
    }

    private static Optional<String> fromWords(List<String> words) {
        int head = -1;
        for (int i = words.size() - 1; i >= 0; i--) {
            if (isHead(words.get(i))) {
                head = i;
                break;
            }
        }
        if (head < 0) return Optional.empty();
        int from = head;
        boolean person = false;
        for (int i = head - 1; i >= 0 && head - i <= MAX_BEFORE; i--) {
            if (personWord(words.get(i))) {
                // A given name (or the surname that is also one) ends the title: it and everything before is dropped.
                person = true;
                break;
            }
            String folded = bare(words.get(i));
            if (STOPS.contains(folded) || !Character.isUpperCase(words.get(i).codePointAt(0))) break;
            from = i;
        }
        // "Ayşe Kaya Depo Müdürü": the window stops at "Kaya", but a given name right before it makes it a surname.
        if (!person && from > 0 && from < head && personWord(words.get(from - 1))) from++;
        List<String> kept = new ArrayList<>();
        for (int i = from; i <= head; i++) kept.add(strip(words.get(i)));
        if (kept.stream().anyMatch(RoleNames::personWord)) return Optional.empty();
        if (kept.size() == 1 && GENERIC.contains(bare(kept.getFirst()))) return Optional.empty();
        String title = String.join(" ", kept);
        return title.length() < 3 || title.length() > 80 ? Optional.empty() : Optional.of(title);
    }

    private static boolean isHead(String word) {
        String folded = bare(word);
        for (String stem : HEADS) {
            // Short stems only as nearly the whole word: "sefi" not inside "sefer", "lead" not "leader's" alike.
            if (folded.startsWith(stem) && (stem.length() >= 5 || folded.length() <= stem.length() + 3)) return true;
        }
        return false;
    }

    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) out.add(matcher.group());
        return out;
    }

    /** The word without a Turkish case suffix after an apostrophe and without trailing dots. */
    private static String strip(String word) {
        int apostrophe = indexOfApostrophe(word);
        String base = apostrophe > 0 ? word.substring(0, apostrophe) : word;
        while (base.endsWith(".")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static String bare(String word) {
        return TurkishText.fold(strip(word));
    }

    private static int indexOfApostrophe(String word) {
        int straight = word.indexOf('\'');
        int curly = word.indexOf('’');
        if (straight < 0) return curly;
        if (curly < 0) return straight;
        return Math.min(straight, curly);
    }
}
