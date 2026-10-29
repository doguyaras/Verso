package com.verso.qa.service.gateway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Turkish personal data in free text (ADR-0059): TCKN, VKN, TR IBAN, payment card numbers, e-mail addresses,
 * Turkish phone numbers and licence plates. Pure and stateless; {@link Pseudonymiser} turns the matches into
 * placeholders.
 *
 * <p>Every number type is checked by its own algorithm (TCKN check digits, VKN check digit, IBAN mod-97, Luhn), not by
 * length alone, so order numbers, dates and amounts stay readable for the model. The VKN check digit passes one random
 * ten-digit number in ten, so a VKN also needs a tax word ("vergi", "VKN") just before it. Over-masking is the safe
 * error: a masked value comes back unchanged in the answer; an unmasked one has left the installation.
 */
final class TurkishPii {

    /** The kinds found; the order is the priority when two matches cover the same text with the same length. */
    enum Type {
        IBAN, CARD, EMAIL, TCKN, PHONE, VKN, PLATE
    }

    /**
     * One finding.
     *
     * @param key the value in a canonical form (separators dropped, lower case), so the same value written two ways
     *            gets one placeholder
     */
    record Match(int start, int end, Type type, String key) {
        int length() {
            return end - start;
        }
    }

    private static final Pattern TCKN = Pattern.compile("(?<![\\d])[1-9]\\d{10}(?!\\d)");
    private static final Pattern VKN = Pattern.compile("(?<![\\d])\\d{10}(?!\\d)");
    /** "vergi no", "Vergi Kimlik No", "VKN", "V.K.N." within this many characters before the number. */
    private static final int VKN_CONTEXT = 40;
    private static final Pattern VKN_WORD = Pattern.compile("(?iu)vergi|v\\.?\\s?k\\.?\\s?n\\b|tax\\s?id");
    private static final Pattern IBAN = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?i:tr)\\d{2}(?:[ ]?\\d{4}){5}[ ]?\\d{2}(?!\\d)");
    private static final Pattern CARD = Pattern.compile("(?<![\\d])(?:"
            + "\\d{4}([ -]?)\\d{4}\\1\\d{4}\\1\\d{4}(?:\\1\\d{1,3})?"   // 16 to 19 digits in fours
            + "|\\d{4}([ -]?)\\d{6}\\2\\d{4,5}"                         // 14 and 15 digits (Amex, Diners)
            + "|\\d{13,19})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\p{L}\\p{N}._%+-])[\\p{L}\\p{N}._%+-]{1,64}@[\\p{L}\\p{N}-]{1,63}(?:\\.[\\p{L}\\p{N}-]{1,63}){1,8}");
    /**
     * +90 / 0090 / 90 / 0, then a three-digit code (in parentheses or not) and seven digits grouped 3-2-2 or 3-4 with
     * spaces, dots or dashes. Groups 1 and 2 tell whether a prefix or parentheses were present.
     */
    private static final Pattern PHONE = Pattern.compile("(?<![\\d+])"
            + "((?:\\+90|0090|90)[ .-]?\\(?0?|0[ .-]?)?"
            + "(\\()?0?(\\d{3})\\)?[ .-]?\\d{3}[ .-]?\\d{2}[ .-]?\\d{2}(?!\\d)");
    /** 01-81, one to three capital letters, two to five digits; the letter-digit combinations are checked in code. */
    private static final Pattern PLATE = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(0[1-9]|[1-7]\\d|8[01])[ ]?([A-Z]{1,3})[ ]?(\\d{2,5})(?![\\p{L}\\p{N}])");
    /** Units and currencies that look like a plate's letters ("16 GB 512", "10 TL 250"). */
    private static final Set<String> NOT_PLATE_LETTERS = Set.of("GB", "MB", "KB", "TB", "PB", "KG", "GR", "KM", "CM",
            "MM", "ML", "LT", "TL", "USD", "EUR", "TRY", "GBP", "KW", "KWH", "MW", "HZ", "MHZ", "GHZ", "PX", "DPI", "RPM",
            "AM", "PM", "SN", "DK", "SA", "NO", "ADT", "AD");

    private TurkishPii() {
    }

    /** The findings in order of position, without overlaps (the longer match wins, then {@link Type} order). */
    static List<Match> find(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<Match> all = new ArrayList<>();
        iban(text, all);
        card(text, all);
        email(text, all);
        tckn(text, all);
        phone(text, all);
        vkn(text, all);
        plate(text, all);
        all.sort(Comparator.comparingInt(Match::length).reversed().thenComparing(Match::type));
        List<Match> kept = new ArrayList<>();
        for (Match m : all) {
            if (kept.stream().noneMatch(k -> m.start() < k.end() && k.start() < m.end())) kept.add(m);
        }
        kept.sort(Comparator.comparingInt(Match::start));
        return kept;
    }

    // ------------------------------------------------------------------------------------------------ detectors

    private static void tckn(String text, List<Match> out) {
        Matcher m = TCKN.matcher(text);
        while (m.find()) {
            if (validTckn(m.group())) out.add(new Match(m.start(), m.end(), Type.TCKN, m.group()));
        }
    }

    private static void vkn(String text, List<Match> out) {
        Matcher m = VKN.matcher(text);
        while (m.find()) {
            if (!validVkn(m.group())) continue;
            String before = text.substring(Math.max(0, m.start() - VKN_CONTEXT), m.start());
            if (VKN_WORD.matcher(before).find()) out.add(new Match(m.start(), m.end(), Type.VKN, m.group()));
        }
    }

    private static void iban(String text, List<Match> out) {
        Matcher m = IBAN.matcher(text);
        while (m.find()) {
            String compact = m.group().replace(" ", "").toUpperCase(Locale.ROOT);
            if (validIban(compact)) out.add(new Match(m.start(), m.end(), Type.IBAN, compact));
        }
    }

    private static void card(String text, List<Match> out) {
        Matcher m = CARD.matcher(text);
        while (m.find()) {
            String digits = digits(m.group());
            if (cardPrefix(digits) && luhn(digits)) out.add(new Match(m.start(), m.end(), Type.CARD, digits));
        }
    }

    private static void email(String text, List<Match> out) {
        Matcher m = EMAIL.matcher(text);
        while (m.find()) {
            String value = m.group();
            // A sentence's full stop is not part of the domain.
            int end = m.end();
            while (value.endsWith(".") || value.endsWith("-")) {
                value = value.substring(0, value.length() - 1);
                end--;
            }
            String tld = value.substring(value.lastIndexOf('.') + 1);
            if (tld.length() < 2 || !tld.chars().allMatch(Character::isLetter)) continue;
            if (value.startsWith(".") || value.contains("..")) continue;
            out.add(new Match(m.start(), end, Type.EMAIL, value.toLowerCase(Locale.ROOT)));
        }
    }

    private static void phone(String text, List<Match> out) {
        Matcher m = PHONE.matcher(text);
        while (m.find()) {
            boolean prefixed = m.group(1) != null;
            boolean parenthesised = m.group(2) != null;
            String code = m.group(3);
            String digits = digits(m.group());
            String national = digits.substring(digits.length() - 10);
            boolean mobile = mobileCode(code);
            // Without +90 / 0 / parentheses only a mobile number is taken: a bare ten-digit number starting with an
            // area code is far more often an order or account number.
            boolean landline = (prefixed || parenthesised) && areaCode(code);
            if (mobile || landline) out.add(new Match(m.start(), m.end(), Type.PHONE, national));
        }
    }

    private static void plate(String text, List<Match> out) {
        Matcher m = PLATE.matcher(text);
        while (m.find()) {
            String letters = m.group(2);
            int numbers = m.group(3).length();
            boolean shape = switch (letters.length()) {
                case 1 -> numbers == 4 || numbers == 5;
                case 2 -> numbers == 3 || numbers == 4;
                default -> numbers == 2 || numbers == 3;
            };
            if (shape && !NOT_PLATE_LETTERS.contains(letters)) {
                out.add(new Match(m.start(), m.end(), Type.PLATE, m.group(1) + letters + m.group(3)));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ checksums

    /**
     * T.C. kimlik numarası: 11 digits, the first not 0; the 10th is (7 × (d1+d3+d5+d7+d9) − (d2+d4+d6+d8)) mod 10, the
     * 11th is the sum of the first ten mod 10.
     */
    static boolean validTckn(String s) {
        if (s.length() != 11 || s.charAt(0) == '0' || !s.chars().allMatch(Character::isDigit)) return false;
        int[] d = s.chars().map(c -> c - '0').toArray();
        int odd = d[0] + d[2] + d[4] + d[6] + d[8];
        int even = d[1] + d[3] + d[5] + d[7];
        int tenth = Math.floorMod(odd * 7 - even, 10);
        int sum = 0;
        for (int i = 0; i < 10; i++) sum += d[i];
        return d[9] == tenth && d[10] == sum % 10;
    }

    /**
     * Vergi kimlik numarası: for the first nine digits, t = (d + 10 − position) mod 10; t is added as is when 9, else
     * (t × 2^(10 − position)) mod 9; the check digit is (10 − sum mod 10) mod 10.
     */
    static boolean validVkn(String s) {
        if (s.length() != 10 || !s.chars().allMatch(Character::isDigit)) return false;
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int position = i + 1;
            int t = (s.charAt(i) - '0' + 10 - position) % 10;
            sum += t == 9 ? 9 : (t * (1 << (10 - position))) % 9;
        }
        return s.charAt(9) - '0' == (10 - sum % 10) % 10;
    }

    /** ISO 13616: a Turkish IBAN is 26 characters; moved and converted, the number mod 97 is 1. */
    static boolean validIban(String compact) {
        if (compact.length() != 26 || !compact.startsWith("TR")) return false;
        String moved = compact.substring(4) + compact.substring(0, 4);
        int remainder = 0;
        for (char c : moved.toCharArray()) {
            int value = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            if (value < 0 || value > 35) return false;
            remainder = (value > 9 ? remainder * 100 : remainder * 10) + value;
            remainder %= 97;
        }
        return remainder == 1;
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (twice) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            twice = !twice;
        }
        return sum % 10 == 0;
    }

    /** Card networks' leading digits (Visa, Mastercard, Amex, Diners, Discover, JCB, UnionPay, Maestro, Troy). */
    private static boolean cardPrefix(String digits) {
        if (digits.length() < 13 || digits.length() > 19) return false;
        if (digits.startsWith("9792")) return digits.length() == 16;
        char first = digits.charAt(0);
        return first >= '2' && first <= '6';
    }

    /** Turkish GSM operator codes: 501-507, 530-559, 561. */
    private static boolean mobileCode(String code) {
        int c = Integer.parseInt(code);
        return (c >= 501 && c <= 507) || (c >= 530 && c <= 559) || c == 561;
    }

    /** Geographic area codes (212-488) and the 850 corporate range. */
    private static boolean areaCode(String code) {
        int c = Integer.parseInt(code);
        return (c >= 212 && c <= 488) || c == 850;
    }

    private static String digits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }
}
