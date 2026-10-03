package com.verso.document.service;

import com.verso.document.api.enums.DocumentFormat;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides on the request path, from the first bytes and the file name only, which parser a file goes to (ADR-0016).
 * Cheap on purpose (no parsing on the upload path, repo-context section 3); the worker decides whether the file really
 * is readable. The client's media type is not trusted: it is whatever the browser guessed.
 *
 * <ul>
 *   <li>PDF: "%PDF-" within the first 1024 bytes (ISO 32000), whatever the name;</li>
 *   <li>DOCX: a ZIP local file header at offset 0 and a ".docx" name;</li>
 *   <li>TXT and MD: a ".txt", ".md" or ".markdown" name and no NUL byte in the first 8 KB, unless a UTF-16 byte order
 *       mark explains them (a binary file renamed to .txt is refused).</li>
 * </ul>
 */
public final class DocumentFormats {

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int PDF_WINDOW = 1024;
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final int TEXT_WINDOW = 8192;

    private DocumentFormats() {}

    public static Optional<DocumentFormat> detect(String fileName, byte[] content) {
        if (startsLikePdf(content)) return Optional.of(DocumentFormat.PDF);
        String name = fileName == null ? "" : fileName.strip().toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return startsWith(content, ZIP_MAGIC) ? Optional.of(DocumentFormat.DOCX) : Optional.empty();
        }
        boolean markdown = name.endsWith(".md") || name.endsWith(".markdown");
        if (markdown || name.endsWith(".txt")) {
            if (!looksLikeText(content)) return Optional.empty();
            return Optional.of(markdown ? DocumentFormat.MD : DocumentFormat.TXT);
        }
        return Optional.empty();
    }

    private static boolean startsLikePdf(byte[] content) {
        int window = Math.min(content.length, PDF_WINDOW) - PDF_MAGIC.length;
        for (int offset = 0; offset <= window; offset++) {
            boolean match = true;
            for (int i = 0; i < PDF_MAGIC.length && match; i++) match = content[offset + i] == PDF_MAGIC[i];
            if (match) return true;
        }
        return false;
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (content[i] != prefix[i]) return false;
        return true;
    }

    /** UTF-16 text has NUL bytes by nature and must say so with its byte order mark; anything else must have none. */
    private static boolean looksLikeText(byte[] content) {
        if (content.length >= 2 && ((content[0] == (byte) 0xFF && content[1] == (byte) 0xFE)
                || (content[0] == (byte) 0xFE && content[1] == (byte) 0xFF))) {
            return true;
        }
        int window = Math.min(content.length, TEXT_WINDOW);
        for (int i = 0; i < window; i++) if (content[i] == 0) return false;
        return true;
    }
}
