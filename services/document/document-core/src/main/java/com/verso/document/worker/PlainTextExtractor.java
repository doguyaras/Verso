package com.verso.document.worker;

import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.config.DocumentProperties;
import com.verso.document.worker.TextSections.Block;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Sections of a TXT or MD file (ADR-0016). The encoding comes from the byte order mark (UTF-8, UTF-16 LE/BE); without
 * one the bytes must be valid UTF-8, and if they are not they are read as Windows-1254, the usual encoding of Turkish
 * text files saved by older Windows programs (every byte sequence is valid there, so the file is never refused for its
 * encoding). Paragraphs are separated by blank lines; in Markdown an ATX heading ("# Title", outside code fences)
 * starts a new section.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class PlainTextExtractor {

    static final Charset TURKISH_WINDOWS = Charset.forName("windows-1254");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}(\\s.*)?$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(```|~~~).*");

    private final DocumentProperties properties;

    public PlainTextExtractor(DocumentProperties properties) {
        this.properties = properties;
    }

    public List<String> extract(byte[] content, boolean markdown) {
        String text = decode(content);
        // The upload looks for NUL in the first 8 KB only; binary data later in the file is refused here (review B3).
        if (text.indexOf('\u0000') >= 0) throw new IngestionRejectedException(DocumentFailureReason.INVALID_FILE);
        return TextSections.split(blocks(text, markdown), properties);
    }

    static String decode(byte[] content) {
        if (startsWith(content, 0xEF, 0xBB, 0xBF)) return strict(StandardCharsets.UTF_8, content, 3);
        if (startsWith(content, 0xFF, 0xFE)) return strict(StandardCharsets.UTF_16LE, content, 2);
        if (startsWith(content, 0xFE, 0xFF)) return strict(StandardCharsets.UTF_16BE, content, 2);
        try {
            return decoder(StandardCharsets.UTF_8).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(content, TURKISH_WINDOWS);
        }
    }

    static List<Block> blocks(String text, boolean markdown) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        boolean inFence = false;
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (markdown && FENCE.matcher(line).matches()) inFence = !inFence;
            boolean heading = markdown && !inFence && HEADING.matcher(line).matches();
            if (heading || line.isBlank()) {
                flush(paragraph, blocks);
                if (heading) blocks.add(new Block(line.strip().replaceFirst("^#+\\s*", ""), true));
            } else {
                if (!paragraph.isEmpty()) paragraph.append('\n');
                paragraph.append(line);
            }
        }
        flush(paragraph, blocks);
        return blocks;
    }

    private static void flush(StringBuilder paragraph, List<Block> blocks) {
        if (!paragraph.isEmpty()) blocks.add(new Block(paragraph.toString(), false));
        paragraph.setLength(0);
    }

    private static String strict(Charset charset, byte[] content, int skip) {
        try {
            return decoder(charset).decode(ByteBuffer.wrap(content, skip, content.length - skip)).toString();
        } catch (CharacterCodingException e) {
            // A byte order mark that promises an encoding the bytes do not follow: not a text file.
            throw new IngestionRejectedException(DocumentFailureReason.INVALID_FILE);
        }
    }

    private static java.nio.charset.CharsetDecoder decoder(Charset charset) {
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((content[i] & 0xFF) != prefix[i]) return false;
        return true;
    }
}
