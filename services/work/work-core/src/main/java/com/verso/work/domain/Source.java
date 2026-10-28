package com.verso.work.domain;

import com.verso.work.api.enums.SkillCitationKind;
import java.util.UUID;

/**
 * One numbered source of a run (ADR-0057): a passage the caller may read, or a part of the caller's own long-text
 * input. {@code [number]} in the prompt and {@code evidence} in the output refer to it. The text and the file name are
 * content: {@link #toString()} prints neither (llm-rules 2.1).
 *
 * @param start INPUT: offset of this part in the input's normalized text
 * @param end   INPUT: end offset (exclusive)
 */
public record Source(int number, SkillCitationKind kind, UUID passageId, UUID documentId, String fileName, Integer page,
                     String unit, UUID collectionId, String collectionName, String inputKey, Integer start,
                     Integer end, String text) {

    public static Source input(int number, String inputKey, int start, int end, String text) {
        return new Source(number, SkillCitationKind.INPUT, null, null, null, null, null, null, null, inputKey, start, end,
                text);
    }

    public Source renumbered(int to) {
        return new Source(to, kind, passageId, documentId, fileName, page, unit, collectionId, collectionName, inputKey,
                start, end, text);
    }

    @Override
    public String toString() {
        return "Source[number=" + number + ", kind=" + kind + "]";
    }
}
