package com.verso.qa.service.gateway;

import static com.verso.qa.service.gateway.TurkishLexicon.TR;

import java.util.Set;

/**
 * Re-fits a case suffix to the value it follows when a placeholder is restored (ADR-0065). The model sees
 * "[AD_1]'in" and may inflect the placeholder by how it reads ("bir" → front vowels); put back after "Yılmaz" the
 * suffix must be "'ın". Applies vowel harmony (two- and four-way), the buffer consonants n/y after a vowel and their
 * removal after a consonant, and d/t, c/ç after a voiceless consonant; "-ki" stays "-ki".
 *
 * <p>Deliberately small: only a suffix written after an apostrophe, only letters, and nothing when the value does not
 * end in a letter (a postal code) or has no vowel (an acronym). A correct suffix comes out unchanged.
 */
final class TurkishSuffix {

    private static final String BACK = "aıou";
    private static final String VOWELS = "aeıioöuü";
    private static final String ROUNDED = "oöuü";
    private static final String VOICELESS = "çfhkpsşt";
    /** Loanword names whose last vowel is back but which take front suffixes ("Kemal'in", "Hilal'e"). */
    private static final Set<String> FRONT_STEMS = Set.of("kemal", "cemal", "celal", "bilal", "hilal", "nihal",
            "ikbal", "iclal", "kemâl", "celâl", "hilâl");

    private TurkishSuffix() {
    }

    /**
     * @param value  the restored value ("Ayşe Yılmaz", "Atatürk Cad. No:12 Çankaya/Ankara")
     * @param suffix the letters after the apostrophe ("in", "nin", "de")
     * @return the suffix fitted to the value's last word; {@code suffix} itself when it cannot be judged
     */
    static String fit(String value, String suffix) {
        if (suffix.isEmpty() || value.isEmpty() || !Character.isLetter(value.charAt(value.length() - 1))) return suffix;
        boolean upper = suffix.codePoints().allMatch(Character::isUpperCase);
        if (!upper && !suffix.codePoints().allMatch(Character::isLowerCase)) return suffix;
        String stem = lastWord(value).toLowerCase(TR).replace('â', 'a').replace('î', 'i').replace('û', 'u');
        char lastVowel = 0;
        for (int i = stem.length() - 1; i >= 0 && lastVowel == 0; i--) {
            if (VOWELS.indexOf(stem.charAt(i)) >= 0) lastVowel = stem.charAt(i);
        }
        if (lastVowel == 0) return suffix;
        if (FRONT_STEMS.contains(stem)) lastVowel = ROUNDED.indexOf(lastVowel) >= 0 ? 'ü' : 'e';
        char stemEnd = stem.charAt(stem.length() - 1);
        boolean stemVowel = VOWELS.indexOf(stemEnd) >= 0;

        StringBuilder s = new StringBuilder(suffix.toLowerCase(TR));
        // Buffer consonants: "Ayşe'nin", "Ayşe'ye" after a vowel; "Yılmaz'ın", "Yılmaz'a" after a consonant.
        if (stemVowel && VOWELS.indexOf(s.charAt(0)) >= 0) {
            boolean genitive = "ıiuü".indexOf(s.charAt(0)) >= 0 && s.length() >= 2 && s.charAt(1) == 'n';
            s.insert(0, genitive ? 'n' : 'y');
        } else if (!stemVowel && s.length() >= 2 && (s.charAt(0) == 'y'
                || (s.charAt(0) == 'n' && VOWELS.indexOf(s.charAt(1)) >= 0))) {
            // A nominal suffix never starts with y after a consonant ("Demir'le", "Ahmet'ti"); n only as the
            // genitive's buffer ("Yılmaz'ın").
            s.deleteCharAt(0);
        }
        // Consonant assimilation: "Ahmet'te", "Ayşe'de"; "Ahmet'çe", "Ayşe'ce".
        char c0 = s.charAt(0);
        boolean voiceless = !stemVowel && VOICELESS.indexOf(stemEnd) >= 0;
        if (c0 == 'd' || c0 == 't') s.setCharAt(0, voiceless ? 't' : 'd');
        if (c0 == 'c' || c0 == 'ç') s.setCharAt(0, voiceless ? 'ç' : 'c');
        // Vowel harmony, left to right; "-ki" after a locative keeps its i.
        char previous = lastVowel;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (VOWELS.indexOf(c) < 0) continue;
            char fitted;
            if ((i >= 3 && s.charAt(i - 1) == 'k' && (s.charAt(i - 2) == 'a' || s.charAt(i - 2) == 'e')
                    && (s.charAt(i - 3) == 'd' || s.charAt(i - 3) == 't') && "ıiuü".indexOf(c) >= 0)) {
                fitted = 'i';
            } else if (c == 'a' || c == 'e') {
                fitted = BACK.indexOf(previous) >= 0 ? 'a' : 'e';
            } else if ("ıiuü".indexOf(c) >= 0) {
                boolean back = BACK.indexOf(previous) >= 0;
                boolean rounded = ROUNDED.indexOf(previous) >= 0;
                fitted = back ? (rounded ? 'u' : 'ı') : (rounded ? 'ü' : 'i');
            } else {
                fitted = c;
            }
            s.setCharAt(i, fitted);
            previous = fitted;
        }
        return upper ? s.toString().toUpperCase(TR) : s.toString();
    }

    private static String lastWord(String value) {
        int i = value.length();
        while (i > 0 && Character.isLetter(value.charAt(i - 1))) i--;
        return value.substring(i);
    }
}
