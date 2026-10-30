package com.verso.qa.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Turkish question asks for, and whether a text gives it (ADR-0064): "kim" a person or a role, "ne zaman" a
 * date or a time, "nerede" a place (or the act that sets something), "kaç" / "ne kadar" a quantity (in the asked unit
 * when the question names one), "hangi şirket" a named company. Lexical and deliberately lenient: a slot counts as
 * given when the text merely could give it, so a correct answer is very rarely refused; what it catches is the answer
 * that clearly gives something else (a "kim" question about the current holder answered with the appointment rule, a
 * "kaç kuruş" question answered without any amount of money, a "hangi binada" question answered with no building).
 *
 * <p>Pure and allocation-light; it runs on the hot path after the chat call. Nothing is logged.
 */
final class QuestionSlot {

    /** The kind of answer a question asks for; {@link #OTHER} has no slot to check ("nedir", "nasıl", yes/no). */
    enum Type { PERSON, DATE, PLACE, NUMBER, ENTITY, OTHER }

    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    private static final String START = "(?<!\\p{L})";
    private static final String END = "(?!\\p{L})";

    // "kim", "kime", "kimin", "kimden", "kimler"; not "kimlik" (identity) or "kimya" (chemistry).
    private static final Pattern PERSON_Q = Pattern.compile(START + "kim(?:ler|lerdir|dir|e|i|in|den|ce)?" + END);
    // Every question word is a whole word with its own endings: "hangi ay" is not "hangi ayrıntılar" or "hangi ayarlar",
    // "hangi yıl" not "hangi yıllık", "kaç" not "kaçak", "kaçış" or "kaçınılmaz" (measured: such questions, typed as a
    // date or a quantity, lost their correct answers to the slot check).
    private static final Pattern DATE_Q = Pattern.compile(
            START + "ne\\s+zaman" + END + "|" + START + "hangi\\s+(?:tarih(?:te|ten|e|i)?|gün(?:de|den|ler|lerde|ü|e)?"
                    + "|ay(?:da|dan|ın|lar|larda|a|ı)?|yıl(?:da|dan|ın|lar|larda|a|ı)?|saat(?:te|ten|ler|lerde|e|i)?)"
                    + END + "|" + START + "kaçta" + END);
    private static final Pattern PLACE_Q = Pattern.compile(
            START + "nere(?:de|ye|den|si|sidir)" + END + "|" + START + "hangi\\s+(?:" + placeNouns() + ")");
    private static final Pattern NUMBER_Q = Pattern.compile(
            START + "kaç(?:ı|a|ta|tır|tir|ar|ıncı|ının|ına)?" + END + "|" + START + "ne\\s+kadar" + END);
    private static final Pattern ENTITY_Q = Pattern.compile(START + "hangi" + END);
    private static final Pattern COMPARISON_Q = Pattern.compile(
            START + "(?:karşılaştır|fark|arasındaki|hangisi|aynı\\s+m[ıi]|farklı\\s+m[ıi])");

    /** Place nouns a "hangi …" question can ask for; the answer must name the same kind of place. */
    private static final List<String> PLACE_NOUNS = List.of("kampüs", "yerleşke", "bina", "şehir", "ilçe", "adres",
            "salon", "oda");
    private static final Pattern NAMED_PLACE = Pattern.compile(START + "hangi\\s+(" + placeNouns() + ")");

    // "şu anki müdür", "halen": the question asks who holds the office, not how it is filled. Not "mevcut" or
    // "güncel": "mevcut yönetmeliğe göre kim onaylar" asks for a role.
    private static final Pattern CURRENT = Pattern.compile(
            START + "(?:şu\\s*an(?:da|ki|dan|a|dır)?|şimdiki|halen|hâlen|günümüzde|bugünkü|bugün(?:e|den)?)" + END);
    private static final Pattern TITLE = Pattern.compile(
            "(?<!\\p{L})(?:Prof|Doç|Dr|Av|Uzm|Öğr|Sn|Sayın)\\.?\\s+\\p{Lu}");
    private static final Pattern CAPITALISED = Pattern.compile("\\p{Lu}[\\p{L}\\p{M}'’]*");
    // Offices, bodies and institutions: capitalised in regulations ("Merkez Müdürü", "Yönetim Kurulu") but not names.
    private static final Pattern OFFICE = Pattern.compile(
            "(?:rektör|dekan|müdür|başkan|kurul|komisyon|senato|bakan|vali|kaymakam|merkez|üniversite|fakülte"
                    + "|enstitü|yüksekokul|bölüm|daire|birim|genel|sekreter|koordinatör|yönetim|danışma|meclis|kurum"
                    + "|başhekim|müsteşar|ofis|hastane|okul|akademi|türkiye|cumhuriyet|kanun|yönetmelik|madde)");
    private static final Pattern ROLE = Pattern.compile(
            "(?:rektör|dekan|müdür|başkan|kurul|komisyon|senato|bakan|vali|kaymakam|amir|yetkili|sorumlu|temsilci"
                    + "|üye|öğretim|personel|işveren|işçi|memur|müfettiş|uzman|hekim|sekreter|koordinatör|danışman"
                    + "|yönetici|kurum|birim|daire|makam|idare|tarafından|kişi|öğrenci|hakem|jüri|vekil|komite|heyet"
                    + "|ofis|merkez|mahkeme|hâkim|hakim|savcı|müdürlü|başkanlı|çalışan|ekip|takım|ekibi)");
    private static final Pattern DATE_WORDS = Pattern.compile(
            "\\d|ocak|şubat|mart|nisan|mayıs|haziran|temmuz|ağustos|eylül|ekim|kasım|aralık|gün|hafta|"
                    + START + "ay" + "|yıl|saat|dakika|tarih|dönem|yarıyıl|başında|sonunda|önce|sonra|içinde|itibaren"
                    + "|kadar|süre|her\\s|takvim|derhal|hemen|gecikmeksizin|zaman");
    private static final Pattern PLACE_WORDS = Pattern.compile(
            "kampüs|yerleşke|bina|blok|" + START + "kat" + END + "|salon|oda" + END + "|adres|cadde|sokak|mahalle"
                    + "|bulvar|şehir|ilçe|internet|web|site|sayfa|ilan|pano|gazete|birim|müdürlüğ|başkanlığ|merkez"
                    + "|fakülte|enstitü|bölüm|ofis|" + START + "yer|kanun|yönetmelik|yönerge|karar|mevzuat|tüzük|genelge"
                    + "|bütçe|sözleşme|portal|sistem|platform");
    // "saat kaçta", "kaça kadar açık": a time of day.
    private static final Pattern TIME_OF_DAY_Q = Pattern.compile(START + "(?:kaça\\s+kadar|kaçta|saat\\s+kaç)");
    private static final Pattern TIME_OF_DAY = Pattern.compile("saat|\\d{1,2}[.:]\\d{2}");
    private static final Pattern UNIT_Q = Pattern.compile(START + "kaç\\s+(\\p{L}+)");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}₺%]+");
    /**
     * Units a "kaç …" question can name, each with what counts as an answer in it: any amount of money, any length of
     * time (a "kaç gün" question may be answered in weeks), people, credits, points, pages.
     */
    private static final List<List<String>> UNITS = List.of(
            List.of("kuruş", "lira", "tl", "₺"),
            List.of("saat", "dakika", "gün", "işgünü", "hafta", "ay", "yıl", "sene", "yarıyıl", "dönem"),
            // A body's size is given in its members of any kind: "4 öğretim elemanı ile müdür ve 2 yardımcısı".
            List.of("kişi", "üye", "eleman", "öğretim", "temsilci", "yardımcı", "personel", "müdür", "başkan",
                    "öğrenci", "işçi", "memur"),
            List.of("kredi", "akts"),
            List.of("puan"),
            List.of("sayfa"));
    // Short unit words ("ay", "tl") are words of their own: "ay" must not match "ayrıca" or "aynı".
    private static final Pattern SHORT_UNIT_ENDING = Pattern.compile(
            "|a|e|ı|i|ya|ye|da|de|ta|te|dan|den|tan|ten|dır|dir|lık|lik|ın|in|lar|ler|lık|lik|su|sı|si");
    private static final Pattern HANGI_COMPANY = Pattern.compile(
            START + "hangi\\s+(?:sigorta\\s+)?(?:şirket|firma|banka|marka|üretici|tedarikçi)");
    // A named company: a legal form, or a capitalised word inside the sentence (not its first word).
    private static final Pattern COMPANY = Pattern.compile(
            "(?<!\\p{L})(?:A\\.?Ş\\.?|Ltd|Şti|Holding|Inc|GmbH)(?!\\p{L})|[\\p{Ll},]\\s+\\p{Lu}\\p{L}+");

    private QuestionSlot() {
    }

    private static String placeNouns() {
        return "kampüs|yerleşke|bina|şehir|ilçe|adres|salon|oda" + END;
    }

    static Type typeOf(String question) {
        String q = lower(question);
        if (PERSON_Q.matcher(q).find()) return Type.PERSON;
        if (DATE_Q.matcher(q).find()) return Type.DATE;
        if (PLACE_Q.matcher(q).find()) return Type.PLACE;
        if (NUMBER_Q.matcher(q).find()) return Type.NUMBER;
        if (ENTITY_Q.matcher(q).find()) return Type.ENTITY;
        return Type.OTHER;
    }

    /**
     * A question that asks several things ("kaç … ve kim …", "… karşılaştır"): a part may be answered and another
     * stated missing, which is a partial answer (ADR-0055), so no single slot is checked.
     */
    static boolean multiPart(String question) {
        String q = lower(question);
        if (COMPARISON_Q.matcher(q).find()) return true;
        Set<Type> kinds = EnumSet.noneOf(Type.class);
        if (PERSON_Q.matcher(q).find()) kinds.add(Type.PERSON);
        if (DATE_Q.matcher(q).find()) kinds.add(Type.DATE);
        if (PLACE_Q.matcher(q).find()) kinds.add(Type.PLACE);
        if (NUMBER_Q.matcher(q).find()) kinds.add(Type.NUMBER);
        // "hangi" counts on its own only when it is not the place or date form already counted.
        Matcher hangi = ENTITY_Q.matcher(q);
        while (hangi.find()) {
            String after = q.substring(hangi.end());
            if (!DATE_Q.matcher("hangi" + after).lookingAt() && !NAMED_PLACE.matcher("hangi" + after).lookingAt()) {
                kinds.add(Type.ENTITY);
            }
        }
        return kinds.size() >= 2;
    }

    /** A yes/no question: the question particle "mı/mi/mu/mü" (with its endings), and no question word. */
    static boolean yesNo(String question) {
        String q = lower(question);
        return YES_NO_Q.matcher(q).find() && typeOf(question) == Type.OTHER;
    }

    private static final Pattern YES_NO_Q = Pattern.compile(START + "m[ıiuü](?:yım|yim|yum|yüm|sın|sin|sun|sün|dır|dir"
            + "|dur|dür|yız|yiz|yuz|yüz)?" + END);

    /** True when the type has a check of its own (a refusal followed by such content may still be an answer). */
    static boolean checkable(Type type, String question) {
        return switch (type) {
            case PERSON, DATE, PLACE, NUMBER -> true;
            case ENTITY -> HANGI_COMPANY.matcher(lower(question)).find();
            case OTHER -> false;
        };
    }

    /**
     * Whether {@code text} (answer text, citation markers removed) gives what {@code question} of {@code type} asks
     * for. A type without a check of its own is always given.
     */
    static boolean filledBy(Type type, String question, String text) {
        String q = lower(question);
        String t = lower(text);
        return switch (type) {
            case PERSON -> CURRENT.matcher(q).find() ? namesSomeone(text, question) : ROLE.matcher(t).find()
                    || capitalisedInside(text);
            case DATE -> DATE_WORDS.matcher(t).find() || hasNumber(text);
            case PLACE -> place(q, t, text);
            case NUMBER -> number(q, t, text);
            case ENTITY -> !HANGI_COMPANY.matcher(q).find() || COMPANY.matcher(text).find();
            case OTHER -> true;
        };
    }

    /** True when some passage states a quantity at all (digits or number words, Turkish or English). */
    static boolean anyNumber(List<String> passages) {
        for (String passage : passages) if (hasNumber(passage)) return true;
        return false;
    }

    private static boolean number(String q, String t, String text) {
        if (!hasNumber(text) && !t.contains("yüzde") && !t.contains("%") && !t.contains("katı")) return false;
        if (TIME_OF_DAY_Q.matcher(q).find()) return TIME_OF_DAY.matcher(t).find();
        Matcher unit = UNIT_Q.matcher(q);
        if (!unit.find()) return true;
        List<String> group = unitGroup(unit.group(1));
        if (group == null) return true;
        Matcher word = WORD.matcher(t);
        while (word.find()) {
            for (String u : group) if (isUnit(word.group(), u)) return true;
        }
        return false;
    }

    private static boolean place(String q, String t, String text) {
        Matcher named = NAMED_PLACE.matcher(q);
        List<String> asked = new ArrayList<>();
        while (named.find()) asked.add(named.group(1));
        if (!asked.isEmpty()) {
            // "hangi kampüste ve hangi binada": each asked kind of place must be named.
            for (String noun : asked) if (!t.contains(noun)) return false;
            return true;
        }
        return PLACE_WORDS.matcher(t).find() || capitalisedInside(text);
    }

    /** A personal name: a title ("Prof. Dr. …") or two adjacent capitalised words that are neither offices nor asked. */
    private static boolean namesSomeone(String text, String question) {
        if (TITLE.matcher(text).find()) return true;
        // Compared word by word: a short name inside a longer question word ("Ali" in "mali", "Can" in "canlı") is
        // not the asked thing.
        Set<String> asked = new HashSet<>();
        Matcher askedWord = WORD.matcher(lower(question));
        while (askedWord.find()) asked.add(stem(askedWord.group()));
        Matcher word = CAPITALISED.matcher(text);
        int previousEnd = -2;
        boolean previousName = false;
        while (word.find()) {
            String w = lower(word.group());
            boolean name = !OFFICE.matcher(w).find() && !asked.contains(stem(w));
            boolean adjacent = previousEnd >= 0 && text.substring(previousEnd, word.start()).isBlank();
            if (adjacent && name && previousName) return true;
            previousEnd = word.end();
            previousName = name;
        }
        return false;
    }

    private static boolean capitalisedInside(String text) {
        return CAPITALISED_INSIDE.matcher(text).find();
    }

    // Two or more number stems written together: "oniki", "kırkbeş", "yirmidört", "yüzelli".
    private static final String NUMBER_STEM = "(?:bir|iki|üç|dört|beş|altı|yedi|sekiz|dokuz|on|yirmi|otuz|kırk|elli"
            + "|altmış|yetmiş|seksen|doksan|yüz|bin)";
    private static final Pattern COMPOUND_NUMBER = Pattern.compile(
            "(?<!\\p{L})(?:on|yirmi|otuz|kırk|elli|altmış|yetmiş|seksen|doksan|yüz|bin)" + NUMBER_STEM + "{1,3}");

    // A capitalised word after another word: a name or an institution, not just the first word of a sentence.
    private static final Pattern CAPITALISED_INSIDE = Pattern.compile("[\\p{L},;]\\s+\\p{Lu}");

    // A lone scale word before a unit or a currency ("bin TL", "yüz puan"): the strict anchor reader drops it as an
    // ordinary word (a face, a ride), but with the unit it is a quantity.
    private static final Pattern LONE_SCALE = Pattern.compile(
            "(?<!\\p{L})(?:yüz|bin|milyon)\\s+(?:" + UNITS.stream().flatMap(List::stream).map(Pattern::quote)
                    .collect(java.util.stream.Collectors.joining("|")) + ")(?!\\p{L})");

    private static boolean hasNumber(String text) {
        // Regulations write compounds as one word ("oniki yarıyıl", "kırkbeş saat"); the anchor reader reads them apart.
        if (COMPOUND_NUMBER.matcher(lower(text)).find()) return true;
        if (LONE_SCALE.matcher(lower(text)).find()) return true;
        for (AnchorCheck.Token token : AnchorCheck.tokens(text, false)) {
            if (token.anchor() != null && token.anchor().startsWith("N:")) return true;
        }
        return false;
    }

    private static List<String> unitGroup(String word) {
        for (List<String> group : UNITS) {
            for (String u : group) if (word.startsWith(u) && (u.length() > 2 || isUnit(word, u))) return group;
        }
        return null;
    }

    private static boolean isUnit(String word, String unit) {
        if (!word.startsWith(unit)) return false;
        return unit.length() > 2 || SHORT_UNIT_ENDING.matcher(word.substring(unit.length())).matches();
    }

    /** The first five letters: enough to tell "Hacettepe" in the question from "Hacettepe'nin" in the answer. */
    private static String stem(String word) {
        String letters = word.replaceAll("['’].*$", "");
        return letters.length() <= 5 ? letters : letters.substring(0, 5);
    }

    private static String lower(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(TURKISH);
    }
}
