package com.verso.document.service;

/**
 * The client's file name, made safe to store and show (reference 9: never a path, never control characters). It is
 * only a label: storage is keyed by the document id, so the name never reaches a file system.
 */
public final class FileNames {

    static final int MAX_CODE_POINTS = 255;
    static final String FALLBACK = "document.pdf";

    private FileNames() {}

    public static String sanitize(String original) {
        if (original == null) return FALLBACK;
        String name = original;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder clean = new StringBuilder(name.length());
        name.codePoints()
                .filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT
                        && Character.getType(c) != Character.SURROGATE && Character.getType(c) != Character.PRIVATE_USE)
                .limit(MAX_CODE_POINTS)
                .forEach(clean::appendCodePoint);
        String result = clean.toString().strip();
        return result.isEmpty() || result.equals(".") || result.equals("..") ? FALLBACK : result;
    }
}
