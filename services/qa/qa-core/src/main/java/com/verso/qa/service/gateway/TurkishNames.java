package com.verso.qa.service.gateway;

import static com.verso.qa.service.gateway.TurkishLexicon.lower;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds Turkish person names (ADR-0065) with rules and word lists, no model: precision first, because a masked
 * institution, law or product name breaks the answer while the cost of a missed name is bounded by the rest of the
 * pipeline (structured identifiers are masked anyway).
 *
 * <p>A name is one of:
 * <ul>
 *   <li>one to three given names and up to two surnames: "Ayşe Yılmaz", "Mehmet Ali Öztürk", "AYŞE YILMAZ", lower case
 *       "ayşe yılmaz" only when both words are listed and the given name is not an ordinary word;</li>
 *   <li>a given name that is never an ordinary word, alone: "Mehmet'e ilet";</li>
 *   <li>an honorific and one to three capitalised words: "Sn. Mehmet Öztürk", "Av. Zeynep Kaya", "Dr. Kaya";</li>
 *   <li>one to three capitalised name words before Bey or Hanım: "Ayşe Hanım", "Yılmaz Bey'e".</li>
 * </ul>
 * Never a name: a given name that is also a word ("Deniz", "Umut", "Bahar", "Metin") without a listed surname or an
 * honorific; anything followed, in the same run of capitalised words, by an institution word ("Mehmet Akif Ersoy
 * Üniversitesi", "Ayşe Yılmaz İnşaat Ltd. Şti.", "Dr. Sadi Konuk Eğitim ve Araştırma Hastanesi"); the phrases of
 * {@code ad-haric.txt} ("Mustafa Kemal Atatürk"). A case suffix after an apostrophe ("Yılmaz'ın") is not part of the
 * match, so every inflected form gets the same placeholder and keeps its own suffix.
 */
final class TurkishNames {

    /** Titles before a name; compared in lower case without the full stop. */
    private static final Set<String> HONORIFIC = Set.of("sn", "sayın", "bay", "bayan", "av", "avukat", "dr", "doktor",
            "doç", "doçent", "prof", "profesör", "uzm", "uzman", "op", "öğr", "gör", "arş", "müh", "mühendis", "ecz",
            "eczacı", "smmm", "ymm", "müdür", "hemşire", "hakim", "hâkim", "savcı", "müşavir");
    /** Words after a name that mark it as a person. */
    private static final Set<String> AFTER_NAME = Set.of("bey", "hanım", "beyefendi", "hanımefendi", "efendi", "hoca",
            "hocam");
    /**
     * The start of a word that makes the capitalised run an institution, a company, a place, a law or a document: a
     * person's name inside it is the institution's name ("Kadir Has Üniversitesi", "Ahmet Yılmaz Lojistik A.Ş.").
     */
    private static final List<String> INSTITUTION = List.of("üniversite", "fakülte", "enstitü", "yüksekokul", "okul",
            "ilkokul", "ortaokul", "lisesi", "lise", "kolej", "hastane", "klinik", "kurum", "kurul", "bakanl",
            "başkanl", "müdürlü", "valili", "kaymakamlı", "belediye", "meclis", "mahkeme", "savcılı", "dairesi",
            "odası", "borsa", "banka", "bank", "holding", "şirket", "grup", "grub", "vakf", "vakıf", "dernek",
            "derneğ", "sendika", "federasyon", "birliğ", "konfederasyon", "kooperatif", "merkez", "ajans", "gazete",
            "parti", "kulüb", "kulüp", "spor", "stad", "salon", "park", "köprü", "havaliman", "liman", "cami",
            "kilise", "müze", "kütüphane", "tiyatro", "sanayi", "ticaret", "inşaat", "turizm", "yayın", "fabrika",
            "kuvvet", "komutanl", "akademi", "teknopark", "teknokent", "plaza", "kampüs", "yerleşke", "ödül", "bursu",
            "fonu", "program", "proje", "kanun", "yönetmeli", "tebliğ", "genelge", "yönerge", "talimat", "prosedür",
            "politika", "sözleşme", "standard", "cadde", "sokak", "sokağ", "mahalle", "bulvar", "sitesi", "apartman",
            "blok", "lojistik", "gıda", "tekstil", "otomotiv", "enerji", "mühendislik", "danışmanlık", "bilişim",
            "yazılım", "teknoloji", "elektronik", "elektrik", "mimarlık", "hukuk", "büro", "ortaklığ", "hizmet",
            "endüstri", "eğitim", "araştırma", "istasyon", "terminal", "otel", "otogar", "şti", "ltd", "anonim",
            "limited", "aş", "inc", "gmbh", "llc", "corp", "sistem", "platform", "uygulama", "modül", "sürüm",
            "enstitüsü", "laboratuvar", "camii", "kurumu", "festival", "fuar", "ödeneğ");
    /** Capitalised words that are never a surname slot: places, months, days, units of an organisation, products. */
    private static final Set<String> COMMON = Set.of("türkiye", "türk", "avrupa", "asya", "anadolu", "ocak", "şubat",
            "mart", "nisan", "mayıs", "haziran", "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık", "pazartesi",
            "salı", "çarşamba", "perşembe", "cuma", "cumartesi", "pazar", "yönetim", "genel", "müdür", "başkan",
            "toplantı", "rapor", "plan", "bütçe", "teklif", "sipariş", "fatura", "ürün", "destek", "donanım", "sunucu",
            "lisans", "paket", "dönem", "yıl", "hafta", "saat", "not", "ek", "madde", "bölüm", "bilgi", "işlem",
            "talep", "onay", "izin", "maaş", "prim", "bordro", "ödeme", "teslim", "tedarik", "satış", "pazarlama",
            "muhasebe", "finans", "insan", "güvenlik", "kalite", "üretim", "depo", "stok", "sigorta", "ekip", "takım",
            "birim", "komite", "komisyon", "excel", "word", "teams", "outlook", "sap", "oracle", "windows", "linux",
            "office", "microsoft", "google", "jira", "slack", "zoom", "ik", "bt", "ve", "ile", "için", "kurban",
            "bayram", "bayramı", "ramazan", "yeni", "eski", "büyük", "küçük", "kuzey", "güney", "doğu", "batı",
            "adres", "telefon", "posta", "şube", "ofis", "hesap", "kart", "numara", "tarih", "konu", "özet", "ad",
            "soyad", "unvan", "görev", "departman");

    private static final Pattern GAP_JOIN = Pattern.compile("[ \\t]+");
    private static final Pattern GAP_INSTITUTION = Pattern.compile("[ \\t]*(?:[&\\-][ \\t]*)?");

    /** A word: letters, an optional suffix after an apostrophe, an optional full stop (abbreviations). */
    record Token(int start, int end, int fullEnd, String word, String lower, boolean suffix, boolean dot) {
        boolean capitalised() {
            if (word.isEmpty() || !Character.isUpperCase(word.codePointAt(0))) return false;
            boolean rest = word.codePoints().skip(1).allMatch(c -> Character.isLowerCase(c) || Character.getType(c)
                    == Character.NON_SPACING_MARK);
            boolean upper = word.length() > 1 && word.codePoints().allMatch(c -> Character.isUpperCase(c)
                    || Character.getType(c) == Character.NON_SPACING_MARK);
            return rest || upper;
        }

        boolean allLower() {
            return word.codePoints().allMatch(c -> Character.isLowerCase(c) || Character.getType(c)
                    == Character.NON_SPACING_MARK);
        }
    }

    private TurkishNames() {
    }

    static void find(String text, List<TurkishPii.Match> out) {
        List<Token> tokens = tokenize(text);
        int i = 0;
        while (i < tokens.size()) {
            int skip = notAName(text, tokens, i);
            if (skip > 0) {
                i += skip;
                continue;
            }
            int[] span = honorific(text, tokens, i);
            if (span == null) span = gazetteer(text, tokens, i);
            if (span == null) span = beforeTitle(text, tokens, i);
            if (span != null && !institution(text, tokens, span[1])) {
                Token first = tokens.get(span[0]);
                Token last = tokens.get(span[1]);
                StringBuilder key = new StringBuilder();
                for (int k = span[0]; k <= span[1]; k++) {
                    if (k > span[0]) key.append(' ');
                    key.append(tokens.get(k).lower());
                }
                out.add(new TurkishPii.Match(first.start(), last.end(), TurkishPii.Type.AD, key.toString()));
                i = span[1] + 1;
                continue;
            }
            // An honorific chain that found no name, or a word that starts nothing: move on.
            i++;
        }
    }

    // ------------------------------------------------------------------------------------------------ rules

    /** "Sn. Mehmet Öztürk", "Prof. Dr. Ali Kaya", "Av. Zeynep Kaya'ya": the honorifics stay outside the match. */
    private static int[] honorific(String text, List<Token> tokens, int i) {
        if (!isHonorific(tokens.get(i))) return null;
        int j = i;
        // At most four titles in a row ("Prof. Dr. Op. Dr."): a longer chain is not a name's and would make the scan
        // quadratic on "Sn. Sn. Sn. …".
        while (j < tokens.size() && j - i < 4 && isHonorific(tokens.get(j))
                && (j == i || joined(text, tokens, j - 1, j, true))) {
            j++;
        }
        if (j >= tokens.size() || !joined(text, tokens, j - 1, j, true)) return null;
        int k = j;
        while (k < tokens.size() && k - j < 3 && (k == j || joined(text, tokens, k - 1, k, false))
                && nameWord(tokens.get(k))) {
            k++;
            if (tokens.get(k - 1).suffix()) break;
        }
        return k > j ? new int[] {j, k - 1} : null;
    }

    /** Given names from the lists, then surnames: "Ayşe Yılmaz", "Mehmet Ali Öztürk", "Mehmet'e", "ayşe yılmaz". */
    private static int[] gazetteer(String text, List<Token> tokens, int i) {
        Token t = tokens.get(i);
        if (t.dot() || !TurkishLexicon.firstName(t.lower())) return null;
        if (t.allLower()) return lowerCaseName(text, tokens, i);
        if (!t.capitalised()) return null;
        int j = i;
        boolean plain = false;
        while (j < tokens.size() && j - i < 3 && (j == i || joined(text, tokens, j - 1, j, false))
                && tokens.get(j).capitalised() && !tokens.get(j).dot()
                && TurkishLexicon.firstName(tokens.get(j).lower())) {
            plain |= TurkishLexicon.plainFirstName(tokens.get(j).lower());
            j++;
            if (tokens.get(j - 1).suffix()) break;
        }
        int k = j;
        while (k < tokens.size() && k - j < 2 && joined(text, tokens, k - 1, k, false)
                && tokens.get(k).capitalised() && !tokens.get(k).dot()
                // An unlisted surname only in the first slot: "Ali Kurtuluş Active Directory" stops at Kurtuluş.
                && surnameWord(tokens.get(k), plain && k == j)) {
            k++;
            if (tokens.get(k - 1).suffix()) break;
        }
        if (k > j) return new int[] {i, k - 1};
        // Only given names: the last one may be a listed surname too ("Ayşe Kaya", "Deniz Yılmaz").
        if (j - i >= 2 && TurkishLexicon.surname(tokens.get(j - 1).lower())) {
            return capitalisedRunEnds(text, tokens, j - 1) ? new int[] {i, j - 1} : null;
        }
        if (!plain) return null;
        // Given names alone, all of them names (at least one never a word), and no unknown capitalised word after
        // them: "Mehmet Ali'ye", "Ayşe ve Ahmet". "Ahmet Proje" or "Murat Kaplan Lojistik" is not taken here.
        return capitalisedRunEnds(text, tokens, j - 1) ? new int[] {i, j - 1} : null;
    }

    /** Lower case only as "ayşe yılmaz": a plain given name and a listed surname. */
    private static int[] lowerCaseName(String text, List<Token> tokens, int i) {
        Token t = tokens.get(i);
        if (!TurkishLexicon.plainFirstName(t.lower()) || t.suffix() || i + 1 >= tokens.size()) return null;
        Token s = tokens.get(i + 1);
        if (!s.allLower() || s.dot() || !joined(text, tokens, i, i + 1, false)) return null;
        return TurkishLexicon.SURNAME.contains(s.lower()) ? new int[] {i, i + 1} : null;
    }

    /** "Ayşe Hanım", "Yılmaz Bey'e", "Ahmet Yılmaz Bey": up to three name words right before Bey or Hanım. */
    private static int[] beforeTitle(String text, List<Token> tokens, int i) {
        int k = i;
        while (k < tokens.size() && k - i < 3 && (k == i || joined(text, tokens, k - 1, k, false))
                && nameWord(tokens.get(k)) && !tokens.get(k).suffix()) {
            k++;
        }
        if (k == i || k >= tokens.size() || !joined(text, tokens, k - 1, k, false)) return null;
        Token after = tokens.get(k);
        return after.capitalised() && AFTER_NAME.contains(after.lower()) ? new int[] {i, k - 1} : null;
    }

    /**
     * Whether the capitalised run that the name at {@code last} ends goes on into an institution word: "Kadir Has
     * Üniversitesi", "Ahmet Yılmaz İnşaat Ltd. Şti.", "Dr. Sadi Konuk Eğitim ve Araştırma Hastanesi".
     */
    private static boolean institution(String text, List<Token> tokens, int last) {
        if (tokens.get(last).suffix()) return false;
        int prev = last;
        for (int k = last + 1, words = 0; k < tokens.size() && words < 6; k++) {
            Token t = tokens.get(k);
            String gap = text.substring(tokens.get(prev).fullEnd(), t.start());
            boolean afterDot = tokens.get(prev).dot();
            if (!GAP_INSTITUTION.matcher(gap).matches() || (gap.isEmpty() && !afterDot)) return false;
            if (t.lower().equals("ve")) {
                prev = k;
                continue;
            }
            if (!t.capitalised()) return false;
            if (institutionWord(t.lower())) return true;
            if (t.suffix()) return false;
            prev = k;
            words++;
        }
        return false;
    }

    /** Whether nothing capitalised but a title follows: a run of given names is not the start of a longer name. */
    private static boolean capitalisedRunEnds(String text, List<Token> tokens, int last) {
        if (tokens.get(last).suffix() || last + 1 >= tokens.size()) return true;
        if (!joined(text, tokens, last, last + 1, false)) return true;
        Token next = tokens.get(last + 1);
        return !next.capitalised() || AFTER_NAME.contains(next.lower()) || next.lower().equals("ve");
    }

    /** {@code ad-haric.txt}: how many tokens a never-a-name phrase covers from {@code i}; 0 when none. */
    private static int notAName(String text, List<Token> tokens, int i) {
        int best = 0;
        for (String[] phrase : TurkishLexicon.NOT_A_NAME) {
            if (i + phrase.length > tokens.size() || phrase.length <= best) continue;
            boolean match = true;
            for (int k = 0; k < phrase.length && match; k++) {
                Token t = tokens.get(i + k);
                match = t.lower().equals(phrase[k]) && (k == 0 || joined(text, tokens, i + k - 1, i + k, false));
            }
            if (match) best = phrase.length;
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ words

    private static boolean isHonorific(Token t) {
        return (t.capitalised() || t.word().length() <= 4) && Character.isUpperCase(t.word().codePointAt(0))
                && HONORIFIC.contains(t.lower()) && !t.suffix();
    }

    /** A capitalised word that can be part of a name after an honorific or before Bey/Hanım. */
    private static boolean nameWord(Token t) {
        String w = t.lower();
        if (!t.capitalised() || t.dot() || w.length() < 2 || HONORIFIC.contains(w) || AFTER_NAME.contains(w)
                || COMMON.contains(w) || institutionWord(w)) {
            return false;
        }
        if (TurkishLexicon.firstName(w) || TurkishLexicon.surname(w)) return true;
        return !TurkishLexicon.PROVINCE.contains(w) && !commonNounEnding(w);
    }

    /**
     * "Yardımcısı", "Dönemi", "Kuvvetleri", "İşler": a plural or possessive ending, or a closing high vowel after a
     * consonant, is far more often a common noun than an unlisted surname.
     */
    private static boolean commonNounEnding(String w) {
        for (String ending : List.of("ları", "leri", "lar", "ler", "sı", "si", "su", "sü")) {
            if (w.endsWith(ending)) return true;
        }
        if (w.length() < 2) return false;
        char lastChar = w.charAt(w.length() - 1);
        char beforeLast = w.charAt(w.length() - 2);
        return "ıiuü".indexOf(lastChar) >= 0 && "aeıioöuü".indexOf(beforeLast) < 0;
    }

    /**
     * A surname after given names: a listed one, a patronymic, or (only after a given name that is never a word) an
     * unknown capitalised word that does not look like a common noun with a possessive or plural ending.
     */
    private static boolean surnameWord(Token t, boolean afterPlainName) {
        String w = t.lower();
        if (TurkishLexicon.surname(w)) return true;
        if (!afterPlainName || w.length() < 3 || COMMON.contains(w) || institutionWord(w) || HONORIFIC.contains(w)
                || AFTER_NAME.contains(w) || TurkishLexicon.PROVINCE.contains(w) || TurkishLexicon.firstName(w)) {
            return false;
        }
        return !commonNounEnding(w);
    }

    static boolean institutionWord(String lower) {
        for (String stem : INSTITUTION) {
            if (lower.startsWith(stem)) return true;
        }
        return false;
    }

    /** Whether tokens a and b are adjacent words of one name: only spaces between, no suffix or full stop on a. */
    private static boolean joined(String text, List<Token> tokens, int a, int b, boolean dotAllowed) {
        Token left = tokens.get(a);
        if (left.suffix() || (left.dot() && !dotAllowed)) return false;
        return GAP_JOIN.matcher(text.substring(left.fullEnd(), tokens.get(b).start())).matches();
    }

    static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            int c = text.codePointAt(i);
            if (!Character.isLetter(c)) {
                i += Character.charCount(c);
                continue;
            }
            int start = i;
            while (i < n && isWordChar(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i));
            int end = i;
            boolean suffix = false;
            boolean dot = false;
            if (i + 1 < n && isApostrophe(text.charAt(i)) && Character.isLetter(text.codePointAt(i + 1))) {
                i++;
                while (i < n && isWordChar(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i));
                suffix = true;
            } else if (i < n && text.charAt(i) == '.') {
                dot = true;
                i++;
            }
            // A word glued to digits ("A4", "Win11") is a code, not a name word.
            if (i < n && Character.isDigit(text.codePointAt(i))) {
                while (i < n && (Character.isLetterOrDigit(text.codePointAt(i)))) i++;
                continue;
            }
            if (start > 0 && Character.isDigit(text.codePointBefore(start))) continue;
            String word = text.substring(start, end);
            tokens.add(new Token(start, end, i, word, lower(word), suffix, dot));
        }
        return tokens;
    }

    private static boolean isWordChar(int c) {
        return Character.isLetter(c) || Character.getType(c) == Character.NON_SPACING_MARK;
    }

    static boolean isApostrophe(char c) {
        return c == '\'' || c == '’' || c == 'ʼ' || c == '`';
    }
}
