package com.verso.qa.service.gateway;

import java.text.Normalizer;

/**
 * A normalised copy of a text with a map back to the original (ADR-0059 review, ADR-0065): detection runs on the copy,
 * the placeholders replace the original spans. Without it a value written with look-alike characters would pass the
 * detectors and leave the installation: a zero-width space inside a TCKN, fullwidth or Arabic-Indic digits, a no-break
 * space inside an IBAN or a phone number, a fullwidth {@code ＠} in an e-mail address.
 *
 * <p>Per code point: format characters ({@code \p{Cf}}: zero-width space and joiner, soft hyphen, BOM, bidi marks) are
 * dropped; every decimal digit ({@code \p{Nd}}) becomes its ASCII digit; everything else is NFKC-normalised (no-break
 * and other spaces become a space, fullwidth letters and signs become ASCII). One original code point may give several
 * characters (a ligature) or none.
 */
final class TextShadow {

    /** The normalised text the detectors read. */
    final String text;
    /** For each character of {@link #text}: the original index where its code point starts. */
    private final int[] from;
    /** For each character of {@link #text}: the original index just after its code point. */
    private final int[] to;
    private final int originalLength;

    private TextShadow(String text, int[] from, int[] to, int originalLength) {
        this.text = text;
        this.from = from;
        this.to = to;
        this.originalLength = originalLength;
    }

    static TextShadow of(String original) {
        StringBuilder out = new StringBuilder(original.length());
        int[] from = new int[original.length() + 8];
        int[] to = new int[original.length() + 8];
        int i = 0;
        while (i < original.length()) {
            int cp = original.codePointAt(i);
            int next = i + Character.charCount(cp);
            String mapped;
            if (Character.getType(cp) == Character.FORMAT) {
                mapped = "";
            } else if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
                mapped = String.valueOf((char) ('0' + Character.digit(cp, 10)));
            } else if (cp < 0x80) {
                mapped = null;
            } else {
                mapped = Normalizer.normalize(original.substring(i, next), Normalizer.Form.NFKC);
            }
            if (mapped == null) {
                from = room(from, out.length() + 1);
                to = room(to, out.length() + 1);
                from[out.length()] = i;
                to[out.length()] = next;
                out.append((char) cp);
            } else {
                from = room(from, out.length() + mapped.length());
                to = room(to, out.length() + mapped.length());
                for (int k = 0; k < mapped.length(); k++) {
                    from[out.length() + k] = i;
                    to[out.length() + k] = next;
                }
                out.append(mapped);
            }
            i = next;
        }
        return new TextShadow(out.toString(), from, to, original.length());
    }

    /** The original start of the shadow span starting at {@code start}. */
    int originalStart(int start) {
        return start < text.length() ? from[start] : originalLength;
    }

    /** The original end of the shadow span ending (exclusive) at {@code end}. */
    int originalEnd(int end) {
        return end == 0 ? 0 : to[end - 1];
    }

    private static int[] room(int[] array, int needed) {
        if (needed <= array.length) return array;
        return java.util.Arrays.copyOf(array, Math.max(needed, array.length * 2));
    }
}
