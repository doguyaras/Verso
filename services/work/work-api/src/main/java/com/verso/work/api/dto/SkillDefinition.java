package com.verso.work.api.dto;

import com.verso.work.api.enums.SkillCategory;
import com.verso.work.api.enums.SkillInputType;
import com.verso.work.api.enums.SkillSectionKind;
import java.util.List;
import java.util.UUID;

/**
 * One task skill (ADR-0057), as written in {@code skills/<id>.json} in the repository and as a verso-admin submits a
 * customer-authored one. The rules (lengths, at most four steps, the disclaimer of a compliance skill, the evidence
 * rule of every row) are enforced by the work module's validator, so a repo file and an admin request fail the same
 * way; the format is documented in {@code skills/README.md}. Optional fields are boxed: a missing value is null, never
 * a silent zero or false.
 *
 * @param formatVersion  the format of this file; 1
 * @param id             {@code [a-z][a-z0-9-]{2,63}}, unique in the installation; never changes
 * @param version        semantic version of the skill's text ({@code 1.0.0}); a run records the version it used
 * @param name           Turkish name shown in the gallery
 * @param description    Turkish one-paragraph description: what goes in, what comes out
 * @param category       gallery group
 * @param slash          the slash command in the ask box ({@code /toplanti}), {@code [a-z0-9-]{2,32}}
 * @param inputs         the guided form, in order
 * @param grounding      which documents are searched and with which queries; null: no retrieval
 * @param steps          the chained prompt pipeline, 1-4 steps; the last one writes the output
 * @param output         the structured output every run must fit
 * @param maxOutputChars length cap of all output text together
 * @param rubric         evaluation dimensions (eval harness, LLM judge)
 * @param framework      the source framework (PMI, ISO 31000, INVEST ...) and its reference
 * @param disclaimer     the label every output carries ("Ön boşluk listesi — denetçi incelemesi için")
 * @param compliance     true for legal and compliance skills: the disclaimer must say who reviews it
 * @param attribution    licence and source of adapted content (Apache-2.0 knowledge-work-plugins); null when original
 * @param forbiddenPhrases phrases the output must never contain ("tamamen uyumlu"); checked deterministically
 */
public record SkillDefinition(Integer formatVersion, String id, String version, String name, String description,
                              SkillCategory category, String slash, List<Input> inputs, Grounding grounding,
                              List<Step> steps, Output output, Integer maxOutputChars, List<RubricDimension> rubric,
                              Framework framework, String disclaimer, Boolean compliance, Attribution attribution,
                              List<String> forbiddenPhrases) {

    /**
     * One form field.
     *
     * @param key       {@code [a-z][a-zA-Z0-9]{1,31}}; referenced as {@code {{key}}} in queries and instructions
     * @param maxLength characters for TEXT and LONG_TEXT
     * @param maxItems  ids for DOCUMENTS and COLLECTIONS
     * @param options   values of a SELECT
     */
    public record Input(String key, String label, SkillInputType type, Boolean required, Integer maxLength,
                        Integer maxItems, List<Option> options, String help, String placeholder) {
    }

    /** One choice of a SELECT field. */
    public record Option(String value, String label) {
    }

    /**
     * Retrieval before reasoning (research design rule 5): the runtime searches with the caller's own access.
     *
     * @param queries          1-3 query templates; {@code {{key}}} is replaced by the input's text (first 300 characters)
     * @param maxPassages      at most this many passages reach the prompt (1-12)
     * @param documentsInput   key of a DOCUMENTS input that limits the search to those documents; null: none
     * @param collectionsInput key of a COLLECTIONS input that limits the search to those collections; null: none
     * @param scopeCollections customer-authored skills: the only collections this skill may search (intersected with
     *                         the caller's access, so a shared skill never widens it); null: every readable document
     */
    public record Grounding(List<String> queries, Integer maxPassages, String documentsInput, String collectionsInput,
                            List<UUID> scopeCollections) {
    }

    /**
     * One link of the chain (pre-mortem, then 5 Whys, then actions). Steps before the last write working notes for
     * the next one; the last writes the structured output.
     *
     * @param maxChars cap of an intermediate step's notes (the last step is capped by {@code maxOutputChars})
     */
    public record Step(String key, String title, String instruction, Integer maxChars) {
    }

    /** The output schema: sections in order. */
    public record Output(List<Section> sections) {
    }

    /**
     * One output section.
     *
     * @param columns   ROWS only: the cells of a row
     * @param minRows   ROWS only: fewer rows is a schema error (0 allowed)
     * @param maxRows   ROWS only: more rows is a schema error
     * @param maxLength TEXT only: characters
     */
    public record Section(String key, String title, SkillSectionKind kind, List<Column> columns, Integer minRows,
                          Integer maxRows, Integer maxLength) {
    }

    /**
     * One cell of a row.
     *
     * @param allowed a closed value list (risk level "Yüksek/Orta/Düşük"); null: free text
     */
    public record Column(String key, String label, Integer maxLength, List<String> allowed) {
    }

    /** One rubric dimension (groundedness, coverage, specificity, actionability, length). */
    public record RubricDimension(String key, String name, String description) {
    }

    /** The framework a skill applies and where it is defined. */
    public record Framework(String name, String reference) {
    }

    /** Provenance of adapted content: source repository, licence and what was adapted. */
    public record Attribution(String source, String license, String url, String note) {
    }
}
