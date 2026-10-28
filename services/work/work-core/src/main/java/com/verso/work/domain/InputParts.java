package com.verso.work.domain;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillDefinition.Input;
import com.verso.work.api.enums.SkillInputType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Splits the caller's long-text inputs (meeting notes, a thread, a requirement list) into numbered INPUT sources, so a
 * row can cite the part of the notes it comes from just like a document passage (ADR-0057). Parts end at a paragraph
 * or line break when there is one in the second half of the window, else at a sentence end, else hard.
 */
public final class InputParts {

    public static final int PART_CHARS = 1500;
    public static final int MAX_PARTS = 24;

    private InputParts() {
    }

    /** INPUT sources numbered from {@code firstNumber}, in form order; at most {@value #MAX_PARTS}. */
    public static List<Source> split(SkillDefinition skill, Map<String, Object> values, int firstNumber) {
        List<Source> parts = new ArrayList<>();
        int number = firstNumber;
        for (Input in : skill.inputs()) {
            if (in.type() != SkillInputType.LONG_TEXT) continue;
            String text = InputRules.text(values, in.key());
            int at = 0;
            while (at < text.length() && parts.size() < MAX_PARTS) {
                int end = cut(text, at);
                String part = text.substring(at, end).strip();
                if (!part.isEmpty()) parts.add(Source.input(number++, in.key(), at, end, part));
                at = end;
            }
        }
        return parts;
    }

    static int cut(String text, int from) {
        int limit = Math.min(text.length(), from + PART_CHARS);
        if (limit == text.length()) return limit;
        int half = from + PART_CHARS / 2;
        int paragraph = text.lastIndexOf('\n', limit - 1);
        if (paragraph >= half) return paragraph + 1;
        for (int i = limit - 1; i >= half; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return limit;
    }
}
