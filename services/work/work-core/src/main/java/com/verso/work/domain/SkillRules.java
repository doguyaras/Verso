package com.verso.work.domain;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillDefinition.Column;
import com.verso.work.api.dto.SkillDefinition.Input;
import com.verso.work.api.dto.SkillDefinition.Section;
import com.verso.work.api.dto.SkillDefinition.Step;
import com.verso.work.api.enums.SkillCategory;
import com.verso.work.api.enums.SkillInputType;
import com.verso.work.api.enums.SkillSectionKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The definition rules of ADR-0057, one place for the repository's skills and an admin's customer-authored ones
 * ({@code skills/README.md} documents them; {@code scripts/skill-library.test.js} mirrors them for the golden sets).
 * Errors are {@code path: rule} lines that never quote a value, so they may be returned and logged.
 */
public final class SkillRules {

    public static final int FORMAT_VERSION = 1;
    public static final int MAX_STEPS = 4;
    public static final int MAX_INPUTS = 10;
    public static final int MAX_SECTIONS = 8;
    public static final int MAX_COLUMNS = 8;
    public static final int MAX_ROWS = 50;
    public static final int MAX_PASSAGES = 12;
    public static final int MAX_QUERIES = 3;
    public static final int MAX_OUTPUT_CHARS = 30_000;
    public static final int MAX_LONG_TEXT = 30_000;
    public static final int MAX_SCOPE_COLLECTIONS = 20;
    /** Licences whose content may be adapted with attribution; source-available ones (anthropics/skills) never. */
    public static final Set<String> LICENSES = Set.of("Apache-2.0", "MIT", "CC-BY-4.0");

    public static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{2,63}");
    public static final Pattern SLASH = Pattern.compile("[a-z0-9-]{2,32}");
    private static final Pattern VERSION = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    private static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9]{1,31}");
    private static final Pattern STEP_KEY = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    private static final Pattern RUBRIC_KEY = Pattern.compile("[a-z][a-z-]{1,31}");
    private static final Pattern OPTION = Pattern.compile("[a-z0-9-]{1,40}");
    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9]+)}}");

    private SkillRules() {
    }

    /**
     * Every rule broken by {@code d}; empty when valid.
     *
     * @param builtIn a repository skill: it may not restrict collections by id (ids differ per installation)
     */
    public static List<String> validate(SkillDefinition d, boolean builtIn) {
        List<String> errors = new ArrayList<>();
        if (d == null) return List.of("definition: required");
        if (d.formatVersion() == null || d.formatVersion() != FORMAT_VERSION) errors.add("formatVersion: must be 1");
        matches(errors, "id", d.id(), ID);
        matches(errors, "version", d.version(), VERSION);
        length(errors, "name", d.name(), 3, 80);
        length(errors, "description", d.description(), 20, 600);
        if (d.category() == null) errors.add("category: required");
        matches(errors, "slash", d.slash(), SLASH);
        Map<String, Input> inputs = inputs(errors, d.inputs());
        grounding(errors, d.grounding(), inputs, builtIn);
        steps(errors, d.steps(), inputs);
        sections(errors, d.output());
        if (d.maxOutputChars() == null || d.maxOutputChars() < 500 || d.maxOutputChars() > MAX_OUTPUT_CHARS) {
            errors.add("maxOutputChars: must be 500.." + MAX_OUTPUT_CHARS);
        }
        rubric(errors, d.rubric());
        if (d.framework() == null) {
            errors.add("framework: required");
        } else {
            length(errors, "framework.name", d.framework().name(), 2, 120);
            length(errors, "framework.reference", d.framework().reference(), 2, 300);
        }
        length(errors, "disclaimer", d.disclaimer(), 10, 200);
        if (d.compliance() == null) errors.add("compliance: required (true or false)");
        boolean compliance = Boolean.TRUE.equals(d.compliance());
        if (d.category() == SkillCategory.LEGAL_COMPLIANCE && !compliance) {
            errors.add("compliance: must be true for LEGAL_COMPLIANCE");
        }
        if (compliance) {
            if (d.disclaimer() != null && !d.disclaimer().toLowerCase(Locale.ROOT).contains("inceleme")) {
                errors.add("disclaimer: a compliance skill must say who reviews it (\"... incelemesi için\")");
            }
            if (d.forbiddenPhrases() == null || d.forbiddenPhrases().isEmpty()) {
                errors.add("forbiddenPhrases: a compliance skill needs at least one (\"tamamen uyumlu\")");
            }
        }
        if (d.forbiddenPhrases() != null) {
            if (d.forbiddenPhrases().size() > 20) errors.add("forbiddenPhrases: at most 20");
            for (int i = 0; i < d.forbiddenPhrases().size(); i++) {
                length(errors, "forbiddenPhrases[" + i + "]", d.forbiddenPhrases().get(i), 2, 80);
            }
        }
        if (d.attribution() != null) {
            var a = d.attribution();
            length(errors, "attribution.source", a.source(), 2, 120);
            length(errors, "attribution.url", a.url(), 8, 300);
            length(errors, "attribution.note", a.note(), 0, 400);
            if (a.license() == null || !LICENSES.contains(a.license())) {
                errors.add("attribution.license: must be one of " + LICENSES.stream().sorted().toList());
            }
            // The document skills of anthropics/skills are source-available, not open source (research S1).
            if (a.source() != null && a.source().trim().toLowerCase(Locale.ROOT).matches("anthropics/skills\\b.*")) {
                errors.add("attribution.source: anthropics/skills content may not be adapted");
            }
        }
        return List.copyOf(errors);
    }

    /** Slash commands and ids must be unique across the installation's skills. */
    public static List<String> uniqueness(List<SkillDefinition> skills) {
        List<String> errors = new ArrayList<>();
        duplicates(errors, "id", skills, SkillDefinition::id);
        duplicates(errors, "slash", skills, SkillDefinition::slash);
        return errors;
    }

    private static void duplicates(List<String> errors, String what, List<SkillDefinition> skills,
                                   Function<SkillDefinition, String> key) {
        Map<String, Long> counts = skills.stream().map(key).filter(k -> k != null)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        counts.forEach((k, n) -> {
            if (n > 1) errors.add(what + ": " + k + " is used " + n + " times");
        });
    }

    private static Map<String, Input> inputs(List<String> errors, List<Input> inputs) {
        Map<String, Input> byKey = new java.util.LinkedHashMap<>();
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_INPUTS) {
            errors.add("inputs: 1.." + MAX_INPUTS + " fields");
            return byKey;
        }
        boolean anyRequired = false;
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            String p = "inputs[" + i + "]";
            if (in == null) {
                errors.add(p + ": required");
                continue;
            }
            matches(errors, p + ".key", in.key(), KEY);
            if (in.key() != null && byKey.put(in.key(), in) != null) errors.add(p + ".key: duplicate");
            length(errors, p + ".label", in.label(), 1, 80);
            length(errors, p + ".help", in.help(), 0, 300);
            length(errors, p + ".placeholder", in.placeholder(), 0, 200);
            if (in.required() == null) errors.add(p + ".required: required (true or false)");
            anyRequired |= Boolean.TRUE.equals(in.required());
            if (in.type() == null) {
                errors.add(p + ".type: required");
                continue;
            }
            switch (in.type()) {
                case TEXT -> range(errors, p + ".maxLength", in.maxLength(), 1, 500);
                case LONG_TEXT -> range(errors, p + ".maxLength", in.maxLength(), 1, MAX_LONG_TEXT);
                case DOCUMENTS, COLLECTIONS -> range(errors, p + ".maxItems", in.maxItems(), 1, 20);
                case SELECT -> {
                    if (in.options() == null || in.options().size() < 2 || in.options().size() > 20) {
                        errors.add(p + ".options: 2..20 options");
                    } else {
                        Set<String> seen = new HashSet<>();
                        for (int j = 0; j < in.options().size(); j++) {
                            var o = in.options().get(j);
                            String op = p + ".options[" + j + "]";
                            if (o == null) {
                                errors.add(op + ": required");
                                continue;
                            }
                            matches(errors, op + ".value", o.value(), OPTION);
                            if (o.value() != null && !seen.add(o.value())) errors.add(op + ".value: duplicate");
                            length(errors, op + ".label", o.label(), 1, 80);
                        }
                    }
                }
            }
        }
        if (!anyRequired) errors.add("inputs: at least one field must be required");
        return byKey;
    }

    private static void grounding(List<String> errors, SkillDefinition.Grounding g, Map<String, Input> inputs,
                                  boolean builtIn) {
        if (g == null) return;
        if (g.queries() == null || g.queries().isEmpty() || g.queries().size() > MAX_QUERIES) {
            errors.add("grounding.queries: 1.." + MAX_QUERIES + " queries");
        } else {
            for (int i = 0; i < g.queries().size(); i++) {
                String p = "grounding.queries[" + i + "]";
                length(errors, p, g.queries().get(i), 2, 300);
                placeholders(errors, p, g.queries().get(i), inputs);
            }
        }
        range(errors, "grounding.maxPassages", g.maxPassages(), 1, MAX_PASSAGES);
        reference(errors, "grounding.documentsInput", g.documentsInput(), inputs, SkillInputType.DOCUMENTS);
        reference(errors, "grounding.collectionsInput", g.collectionsInput(), inputs, SkillInputType.COLLECTIONS);
        if (g.scopeCollections() != null) {
            if (builtIn) errors.add("grounding.scopeCollections: only customer-authored skills restrict collections");
            if (g.scopeCollections().isEmpty() || g.scopeCollections().size() > MAX_SCOPE_COLLECTIONS
                    || g.scopeCollections().stream().anyMatch(java.util.Objects::isNull)) {
                errors.add("grounding.scopeCollections: 1.." + MAX_SCOPE_COLLECTIONS + " collection ids");
            }
        }
    }

    private static void reference(List<String> errors, String path, String key, Map<String, Input> inputs,
                                  SkillInputType type) {
        if (key == null) return;
        Input in = inputs.get(key);
        if (in == null || in.type() != type) errors.add(path + ": must name a " + type + " input");
    }

    private static void steps(List<String> errors, List<Step> steps, Map<String, Input> inputs) {
        if (steps == null || steps.isEmpty() || steps.size() > MAX_STEPS) {
            errors.add("steps: 1.." + MAX_STEPS + " steps");
            return;
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            String p = "steps[" + i + "]";
            if (s == null) {
                errors.add(p + ": required");
                continue;
            }
            matches(errors, p + ".key", s.key(), STEP_KEY);
            if (s.key() != null && !keys.add(s.key())) errors.add(p + ".key: duplicate");
            length(errors, p + ".title", s.title(), 1, 80);
            length(errors, p + ".instruction", s.instruction(), 20, 4000);
            placeholders(errors, p + ".instruction", s.instruction(), inputs);
            boolean last = i == steps.size() - 1;
            if (last && s.maxChars() != null) errors.add(p + ".maxChars: the last step is capped by maxOutputChars");
            if (!last) range(errors, p + ".maxChars", s.maxChars(), 200, 6000);
        }
    }

    private static void sections(List<String> errors, SkillDefinition.Output output) {
        if (output == null || output.sections() == null || output.sections().isEmpty()
                || output.sections().size() > MAX_SECTIONS) {
            errors.add("output.sections: 1.." + MAX_SECTIONS + " sections");
            return;
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < output.sections().size(); i++) {
            Section s = output.sections().get(i);
            String p = "output.sections[" + i + "]";
            if (s == null) {
                errors.add(p + ": required");
                continue;
            }
            matches(errors, p + ".key", s.key(), KEY);
            if (s.key() != null && !keys.add(s.key())) errors.add(p + ".key: duplicate");
            length(errors, p + ".title", s.title(), 1, 80);
            if (s.kind() == null) {
                errors.add(p + ".kind: required");
            } else if (s.kind() == SkillSectionKind.TEXT) {
                range(errors, p + ".maxLength", s.maxLength(), 1, 4000);
                if (s.columns() != null || s.minRows() != null || s.maxRows() != null) {
                    errors.add(p + ": a TEXT section has no columns or row limits");
                }
            } else {
                if (s.maxLength() != null) errors.add(p + ".maxLength: a ROWS section is capped per column");
                range(errors, p + ".maxRows", s.maxRows(), 1, MAX_ROWS);
                range(errors, p + ".minRows", s.minRows(), 0, s.maxRows() == null ? MAX_ROWS : s.maxRows());
                columns(errors, p, s.columns());
            }
        }
    }

    private static void columns(List<String> errors, String p, List<Column> columns) {
        if (columns == null || columns.isEmpty() || columns.size() > MAX_COLUMNS) {
            errors.add(p + ".columns: 1.." + MAX_COLUMNS + " columns");
            return;
        }
        Set<String> keys = new HashSet<>();
        for (int j = 0; j < columns.size(); j++) {
            Column c = columns.get(j);
            String cp = p + ".columns[" + j + "]";
            if (c == null) {
                errors.add(cp + ": required");
                continue;
            }
            matches(errors, cp + ".key", c.key(), KEY);
            if ("evidence".equals(c.key())) errors.add(cp + ".key: \"evidence\" is reserved");
            if (c.key() != null && !keys.add(c.key())) errors.add(cp + ".key: duplicate");
            length(errors, cp + ".label", c.label(), 1, 80);
            range(errors, cp + ".maxLength", c.maxLength(), 1, 2000);
            if (c.allowed() != null) {
                if (c.allowed().isEmpty() || c.allowed().size() > 10) errors.add(cp + ".allowed: 1..10 values");
                for (int k = 0; k < c.allowed().size(); k++) length(errors, cp + ".allowed[" + k + "]", c.allowed().get(k), 1, 40);
            }
        }
    }

    private static void rubric(List<String> errors, List<SkillDefinition.RubricDimension> rubric) {
        if (rubric == null || rubric.size() < 3 || rubric.size() > 8) {
            errors.add("rubric: 3..8 dimensions");
            return;
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < rubric.size(); i++) {
            var r = rubric.get(i);
            String p = "rubric[" + i + "]";
            if (r == null) {
                errors.add(p + ": required");
                continue;
            }
            matches(errors, p + ".key", r.key(), RUBRIC_KEY);
            if (r.key() != null && !keys.add(r.key())) errors.add(p + ".key: duplicate");
            length(errors, p + ".name", r.name(), 2, 80);
            length(errors, p + ".description", r.description(), 10, 400);
        }
        // Research section 6: every skill is judged at least on groundedness and length (verbosity is a known failure).
        if (!keys.contains("groundedness")) errors.add("rubric: needs a \"groundedness\" dimension");
        if (!keys.contains("length")) errors.add("rubric: needs a \"length\" dimension");
    }

    /** A {@code {{key}}} may only name a text or select input: ids are never pasted into a prompt or a query. */
    private static void placeholders(List<String> errors, String path, String text, Map<String, Input> inputs) {
        if (text == null) return;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            Input in = inputs.get(m.group(1));
            if (in == null || in.type() == SkillInputType.DOCUMENTS || in.type() == SkillInputType.COLLECTIONS) {
                errors.add(path + ": {{" + m.group(1) + "}} must name a text or select input");
            }
        }
    }

    private static void matches(List<String> errors, String path, String value, Pattern pattern) {
        if (value == null || !pattern.matcher(value).matches()) errors.add(path + ": must match " + pattern.pattern());
    }

    private static void length(List<String> errors, String path, String value, int min, int max) {
        if (value == null) {
            if (min > 0) errors.add(path + ": required");
            return;
        }
        int n = value.strip().length();
        if (n < min || value.length() > max) errors.add(path + ": length must be " + min + ".." + max);
    }

    private static void range(List<String> errors, String path, Integer value, int min, int max) {
        if (value == null || value < min || value > max) errors.add(path + ": must be " + min + ".." + max);
    }
}
