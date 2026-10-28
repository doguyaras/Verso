package com.verso.work.api.dto;

import java.util.List;
import java.util.Map;

/**
 * The structured output of a run (ADR-0057), section by section in the order of the skill's output schema. Every row
 * and every text section either cites sources by their number ({@code evidence}, the numbers of
 * {@link SkillRunResponse#citations()}) or says {@value #NOT_ASSESSABLE} ({@code notAssessable = true}, no evidence):
 * a verdict without evidence is never stored (research design rule 2).
 *
 * @param sections in schema order
 */
public record SkillOutput(List<Section> sections) {

    /** The only allowed answer when nothing in the sources supports a row. */
    public static final String NOT_ASSESSABLE = "değerlendirilemez";

    /**
     * One section.
     *
     * @param key           the schema section's key
     * @param text          TEXT sections: the paragraph
     * @param evidence      TEXT sections: cited source numbers
     * @param notAssessable TEXT sections: true when nothing supports it
     * @param rows          ROWS sections: the rows
     */
    public record Section(String key, String text, List<Integer> evidence, Boolean notAssessable, List<Row> rows) {
    }

    /**
     * One row of a ROWS section.
     *
     * @param cells         column key to text
     * @param evidence      cited source numbers; empty when notAssessable
     * @param notAssessable true: "değerlendirilemez"
     * @param unverified    numbers and codes of the row that its cited sources do not contain (ADR-0047 check);
     *                      set by the server, ignored on input
     */
    public record Row(Map<String, String> cells, List<Integer> evidence, Boolean notAssessable,
                      List<String> unverified) {
    }
}
