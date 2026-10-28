package com.verso.work.domain;

import com.verso.platform.core.text.TurkishText;
import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillDefinition.Column;
import com.verso.work.api.dto.SkillDefinition.Section;
import com.verso.work.api.dto.SkillOutput;
import com.verso.work.api.enums.SkillSectionKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The output contract of ADR-0057: turns the model's JSON into a {@link SkillOutput} and checks any output (the
 * model's or the owner's edit) against the skill's schema. Every row and text section either cites existing source
 * numbers or says "değerlendirilemez"; lengths, row limits, closed value lists, the total length cap and the
 * forbidden phrases are enforced. Errors name sections, rows and rules, never the text (they are logged and sent to
 * the model in the repair prompt, which already has the text).
 */
public final class OutputSchema {

    /** The model's answer, its parsed output (null when not even the JSON could be read) and the problems. */
    public record Checked(SkillOutput output, List<String> errors) {

        public boolean valid() {
            return output != null && errors.isEmpty();
        }
    }

    private static final String NOT_ASSESSABLE_FOLDED = TurkishText.fold(SkillOutput.NOT_ASSESSABLE);
    private static final int MAX_ERRORS = 30;

    private OutputSchema() {
    }

    /**
     * Parses the model's text: one JSON object, optionally inside a code fence or with chatter around it.
     *
     * @param reader turns JSON text into maps and lists (the module's JsonMapper); throws on invalid JSON
     */
    public static Checked parse(SkillDefinition skill, String modelText, int sourceCount,
                                Function<String, Object> reader) {
        String json = extractObject(modelText);
        if (json == null) return new Checked(null, List.of("output: no JSON object"));
        Object root;
        try {
            root = reader.apply(json);
        } catch (RuntimeException e) {
            return new Checked(null, List.of("output: invalid JSON"));
        }
        if (!(root instanceof Map<?, ?> top) || !(top.get("sections") instanceof Map<?, ?> sections)) {
            return new Checked(null, List.of("output: needs an object \"sections\""));
        }
        List<String> errors = new ArrayList<>();
        List<SkillOutput.Section> out = new ArrayList<>();
        for (Section s : skill.output().sections()) {
            Object raw = sections.get(s.key());
            if (!(raw instanceof Map<?, ?> value)) {
                errors.add("sections." + s.key() + ": missing");
                continue;
            }
            if (s.kind() == SkillSectionKind.TEXT) {
                Evidence ev = evidence(value.get("evidence"));
                if (ev.error() != null) errors.add("sections." + s.key() + ".evidence: " + ev.error());
                out.add(new SkillOutput.Section(s.key(), string(value.get("text")), ev.numbers(), ev.notAssessable(),
                        null));
            } else {
                if (!(value.get("rows") instanceof List<?> rows)) {
                    errors.add("sections." + s.key() + ".rows: must be a list");
                    continue;
                }
                List<SkillOutput.Row> parsed = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    if (!(rows.get(i) instanceof Map<?, ?> row)) {
                        errors.add("sections." + s.key() + ".rows[" + i + "]: must be an object");
                        continue;
                    }
                    Map<String, String> cells = new LinkedHashMap<>();
                    for (Column c : s.columns()) cells.put(c.key(), string(row.get(c.key())));
                    Evidence ev = evidence(row.get("evidence"));
                    if (ev.error() != null) errors.add("sections." + s.key() + ".rows[" + i + "].evidence: " + ev.error());
                    parsed.add(new SkillOutput.Row(cells, ev.numbers(), ev.notAssessable(), List.of()));
                }
                out.add(new SkillOutput.Section(s.key(), null, null, null, parsed));
            }
        }
        SkillOutput output = new SkillOutput(out);
        if (!errors.isEmpty()) return new Checked(output, cap(errors));
        return check(skill, output, sourceCount);
    }

    /**
     * Checks and normalizes an output against the schema: the model's after parsing, or the owner's edit. The result's
     * output has the sections in schema order, cells trimmed, closed values in their canonical spelling and
     * {@code unverified} cleared (the grounding check sets it afterwards).
     */
    public static Checked check(SkillDefinition skill, SkillOutput output, int sourceCount) {
        List<String> errors = new ArrayList<>();
        if (output == null || output.sections() == null) return new Checked(null, List.of("output: required"));
        Map<String, SkillOutput.Section> byKey = new LinkedHashMap<>();
        for (SkillOutput.Section s : output.sections()) {
            if (s != null && s.key() != null) byKey.putIfAbsent(s.key(), s);
        }
        List<SkillOutput.Section> normalized = new ArrayList<>();
        int total = 0;
        for (Section schema : skill.output().sections()) {
            SkillOutput.Section s = byKey.get(schema.key());
            String p = "sections." + schema.key();
            if (s == null) {
                errors.add(p + ": missing");
                continue;
            }
            if (schema.kind() == SkillSectionKind.TEXT) {
                String text = trim(s.text());
                boolean na = Boolean.TRUE.equals(s.notAssessable());
                List<Integer> ev = s.evidence() == null ? List.of() : s.evidence();
                if (text.isEmpty()) errors.add(p + ".text: required");
                if (text.length() > schema.maxLength()) errors.add(p + ".text: at most " + schema.maxLength() + " characters");
                evidenceRule(errors, p, ev, na, sourceCount);
                total += text.length();
                normalized.add(new SkillOutput.Section(schema.key(), text, na ? List.of() : List.copyOf(ev), na, null));
            } else {
                List<SkillOutput.Row> rows = s.rows() == null ? List.of() : s.rows();
                if (rows.size() < schema.minRows() || rows.size() > schema.maxRows()) {
                    errors.add(p + ".rows: " + schema.minRows() + ".." + schema.maxRows() + " rows");
                }
                List<SkillOutput.Row> out = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    SkillOutput.Row row = rows.get(i);
                    String rp = p + ".rows[" + i + "]";
                    if (row == null) {
                        errors.add(rp + ": required");
                        continue;
                    }
                    Map<String, String> cells = new LinkedHashMap<>();
                    Map<String, String> given = row.cells() == null ? Map.of() : row.cells();
                    for (Column c : schema.columns()) {
                        String value = trim(given.get(c.key()));
                        if (value.length() > c.maxLength()) {
                            errors.add(rp + "." + c.key() + ": at most " + c.maxLength() + " characters");
                        }
                        if (c.allowed() != null && !value.isEmpty()) {
                            String canonical = canonical(c.allowed(), value);
                            if (canonical == null) errors.add(rp + "." + c.key() + ": must be one of the allowed values");
                            else value = canonical;
                        }
                        total += value.length();
                        cells.put(c.key(), value);
                    }
                    if (cells.get(schema.columns().getFirst().key()).isEmpty()) {
                        errors.add(rp + "." + schema.columns().getFirst().key() + ": required");
                    }
                    boolean na = Boolean.TRUE.equals(row.notAssessable());
                    List<Integer> ev = row.evidence() == null ? List.of() : row.evidence();
                    evidenceRule(errors, rp, ev, na, sourceCount);
                    out.add(new SkillOutput.Row(cells, na ? List.of() : List.copyOf(ev), na, List.of()));
                }
                normalized.add(new SkillOutput.Section(schema.key(), null, null, null, List.copyOf(out)));
            }
        }
        if (total > skill.maxOutputChars()) errors.add("output: at most " + skill.maxOutputChars() + " characters in total");
        SkillOutput result = new SkillOutput(List.copyOf(normalized));
        if (skill.forbiddenPhrases() != null) {
            String folded = TurkishText.fold(text(result));
            for (String phrase : skill.forbiddenPhrases()) {
                if (folded.contains(TurkishText.fold(phrase))) errors.add("output: contains a forbidden phrase");
            }
        }
        return new Checked(result, cap(errors));
    }

    /** All text of an output, for the length and forbidden-phrase checks and the export. */
    public static String text(SkillOutput output) {
        StringBuilder all = new StringBuilder();
        for (SkillOutput.Section s : output.sections()) {
            if (s.text() != null) all.append(s.text()).append('\n');
            if (s.rows() != null) {
                for (SkillOutput.Row r : s.rows()) r.cells().values().forEach(v -> all.append(v).append('\n'));
            }
        }
        return all.toString();
    }

    private static void evidenceRule(List<String> errors, String path, List<Integer> evidence, boolean notAssessable,
                                     int sourceCount) {
        if (notAssessable) {
            if (!evidence.isEmpty()) errors.add(path + ".evidence: \"değerlendirilemez\" has no evidence");
            return;
        }
        if (evidence.isEmpty()) {
            errors.add(path + ".evidence: cite sources or say \"değerlendirilemez\"");
            return;
        }
        if (evidence.size() > 10) errors.add(path + ".evidence: at most 10 sources");
        for (Integer n : evidence) {
            if (n == null || n < 1 || n > sourceCount) {
                errors.add(path + ".evidence: source " + n + " does not exist");
                return;
            }
        }
    }

    private record Evidence(List<Integer> numbers, boolean notAssessable, String error) {
    }

    /** {@code [1, 3]} or the word "değerlendirilemez" (any case, with or without Turkish letters). */
    private static Evidence evidence(Object raw) {
        if (raw instanceof String s && TurkishText.fold(s.strip()).equals(NOT_ASSESSABLE_FOLDED)) {
            return new Evidence(List.of(), true, null);
        }
        if (raw instanceof List<?> list) {
            List<Integer> numbers = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
                    if (!numbers.contains(n.intValue())) numbers.add(n.intValue());
                } else {
                    return new Evidence(List.of(), false, "must be source numbers");
                }
            }
            return new Evidence(numbers, false, null);
        }
        return new Evidence(List.of(), false, "cite sources or say \"değerlendirilemez\"");
    }

    private static String canonical(List<String> allowed, String value) {
        String folded = TurkishText.fold(value);
        for (String a : allowed) if (TurkishText.fold(a).equals(folded)) return a;
        return null;
    }

    private static String string(Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return "";
    }

    private static String trim(String value) {
        return value == null ? "" : InputRules.clean(value);
    }

    /** The outermost JSON object of the model's text: code fences and chatter around it are ignored. */
    static String extractObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start < 0 || end <= start ? null : text.substring(start, end + 1);
    }

    private static List<String> cap(List<String> errors) {
        return List.copyOf(errors.stream().distinct().limit(MAX_ERRORS).toList());
    }
}
