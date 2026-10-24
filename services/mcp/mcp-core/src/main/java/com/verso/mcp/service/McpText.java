package com.verso.mcp.service;

/**
 * What document text looks like when it leaves through MCP (ADR-0031). The text goes to another model as tool output,
 * so the characters that hide or reorder text without showing it are removed: control characters (except line breaks
 * and tabs in a passage), format characters (zero-width, bidirectional overrides, Unicode tag characters that can
 * carry an invisible instruction) and lone surrogates. Lengths count code points, never split a surrogate pair, and a
 * cut is marked with an ellipsis.
 */
public final class McpText {

    static final String ELLIPSIS = "…";

    private McpText() {}

    /** Passage text: line breaks and tabs stay, runs of blank lines shrink to one, at most {@code max} code points. */
    public static String passage(String text, int max) {
        String clean = strip(text, true).replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
        return cut(clean, max);
    }

    /** One line: every whitespace run (line breaks included) becomes one space, at most {@code max} code points. */
    public static String line(String text, int max) {
        return cut(strip(text, false).replaceAll("\\s+", " ").strip(), max);
    }

    private static String strip(String text, boolean keepLines) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (c == '\n' || c == '\t') {
                out.append(keepLines ? (char) c : ' ');
                return;
            }
            if (c == '\r') return;
            int type = Character.getType(c);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.UNASSIGNED) {
                return;
            }
            if (type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                out.append(keepLines ? '\n' : ' ');
                return;
            }
            out.appendCodePoint(c);
        });
        return out.toString();
    }

    private static String cut(String text, int max) {
        int count = text.codePointCount(0, text.length());
        if (count <= max) return text;
        int end = text.offsetByCodePoints(0, Math.max(0, max - 1));
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }
}
