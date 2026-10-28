package com.verso.work.domain;

import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillDefinition.Input;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Checks a run's form values against the skill's form (ADR-0057) and returns them normalized: text stripped and
 * control characters other than line breaks and tabs removed, ids as distinct UUID strings. Errors name the field and
 * the rule, never the value: the inputs are the caller's data (llm-rules 2.1).
 */
public final class InputRules {

    /** The normalized values, or the problems. */
    public record Result(Map<String, Object> values, List<String> errors) {

        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private InputRules() {
    }

    public static Result check(SkillDefinition skill, Map<String, Object> raw) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> given = raw == null ? Map.of() : raw;
        Set<String> known = new LinkedHashSet<>();
        for (Input in : skill.inputs()) known.add(in.key());
        for (String key : given.keySet()) {
            if (!known.contains(key)) errors.add("inputs: unknown field");
        }
        for (Input in : skill.inputs()) {
            Object value = given.get(in.key());
            boolean required = Boolean.TRUE.equals(in.required());
            String p = in.key();
            switch (in.type()) {
                case TEXT, LONG_TEXT, SELECT -> {
                    if (value != null && !(value instanceof String)) {
                        errors.add(p + ": must be text");
                        continue;
                    }
                    String text = value == null ? "" : clean((String) value);
                    if (text.isEmpty()) {
                        if (required) errors.add(p + ": required");
                        continue;
                    }
                    if (in.maxLength() != null && text.length() > in.maxLength()) {
                        errors.add(p + ": at most " + in.maxLength() + " characters");
                        continue;
                    }
                    if (in.type() == com.verso.work.api.enums.SkillInputType.SELECT
                            && in.options().stream().noneMatch(o -> o.value().equals(text))) {
                        errors.add(p + ": not one of the options");
                        continue;
                    }
                    values.put(p, text);
                }
                case DOCUMENTS, COLLECTIONS -> {
                    if (value != null && !(value instanceof List<?>)) {
                        errors.add(p + ": must be a list of ids");
                        continue;
                    }
                    List<?> list = value == null ? List.of() : (List<?>) value;
                    Set<String> ids = new LinkedHashSet<>();
                    boolean bad = false;
                    for (Object item : list) {
                        if (!(item instanceof String s) || !uuid(s)) {
                            bad = true;
                            break;
                        }
                        ids.add(UUID.fromString(s).toString());
                    }
                    if (bad) {
                        errors.add(p + ": must be a list of ids");
                        continue;
                    }
                    if (ids.isEmpty()) {
                        if (required) errors.add(p + ": required");
                        continue;
                    }
                    if (in.maxItems() != null && ids.size() > in.maxItems()) {
                        errors.add(p + ": at most " + in.maxItems() + " items");
                        continue;
                    }
                    values.put(p, List.copyOf(ids));
                }
            }
        }
        return new Result(errors.isEmpty() ? values : Map.of(), List.copyOf(errors.stream().distinct().toList()));
    }

    /** Stripped, with control and format characters removed except line breaks and tabs (prompt hygiene). */
    static String clean(String text) {
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (c == '\n' || c == '\t' || (!Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)) {
                out.appendCodePoint(c == '\t' ? ' ' : c);
            }
        });
        return out.toString().replace("\r", "").strip();
    }

    private static boolean uuid(String s) {
        if (s.length() != 36) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The text of a value for a {@code {{key}}} placeholder: SELECT gives its value, lists give nothing. */
    public static String text(Map<String, Object> values, String key) {
        return values.get(key) instanceof String s ? s : "";
    }

    /** The ids of a DOCUMENTS or COLLECTIONS value; empty when absent. */
    public static List<UUID> ids(Map<String, Object> values, String key) {
        if (key == null || !(values.get(key) instanceof List<?> list)) return List.of();
        return list.stream().map(o -> UUID.fromString((String) o)).toList();
    }
}
