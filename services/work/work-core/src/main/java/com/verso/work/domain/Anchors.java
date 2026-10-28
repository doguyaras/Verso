package com.verso.work.domain;

import com.verso.platform.core.text.TurkishText;
import com.verso.work.api.dto.SkillOutput;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The groundedness check of ADR-0047 applied to skill output (ADR-0057): every number and code a row or a text
 * section states must appear in the sources it cites, or in the caller's own inputs (a number the user wrote may be
 * repeated). The output is never changed; the row only lists what could not be verified, and the panel flags it.
 * Same code definition as the answer check and the system model ({@link TurkishText#CODE_REGEX}); numbers are compared
 * with their digits only, so "50.000", "50,000" and "50000" read alike.
 */
public final class Anchors {

    /** A number; only those of at least two digits are checked (single digits are list numbers too often). */
    private static final Pattern NUMBER =
            Pattern.compile("(?<![\\p{L}\\p{N}])\\d+(?:[.,\\u00A0]\\d{3})*(?:[.,]\\d+)?(?![\\p{L}\\p{N}])");
    private static final Pattern CODE = Pattern.compile(TurkishText.CODE_REGEX);
    private static final int MAX_LISTED = 10;

    /** The checked output and how many rows and text sections carry an unverified anchor. */
    public record Result(SkillOutput output, int unverifiedRows) {
    }

    private Anchors() {
    }

    /**
     * @param sources    the run's numbered sources ({@code number} 1..n, in order)
     * @param inputsText the caller's text inputs joined (TEXT, LONG_TEXT, SELECT values)
     */
    public static Result check(SkillOutput output, List<Source> sources, String inputsText) {
        String inputs = digitsAndFolded(inputsText);
        List<SkillOutput.Section> sections = new ArrayList<>();
        int unverified = 0;
        for (SkillOutput.Section s : output.sections()) {
            if (s.rows() == null) {
                sections.add(s);
                continue;
            }
            List<SkillOutput.Row> rows = new ArrayList<>();
            for (SkillOutput.Row row : s.rows()) {
                List<String> missing = Boolean.TRUE.equals(row.notAssessable()) ? List.of()
                        : missing(String.join("\n", row.cells().values()), cited(row.evidence(), sources), inputs);
                if (!missing.isEmpty()) unverified++;
                rows.add(new SkillOutput.Row(row.cells(), row.evidence(), row.notAssessable(), missing));
            }
            sections.add(new SkillOutput.Section(s.key(), s.text(), s.evidence(), s.notAssessable(), List.copyOf(rows)));
        }
        // A text section has no unverified list of its own: it counts, and the panel shows the count in the checks.
        for (SkillOutput.Section s : output.sections()) {
            if (s.text() != null && !Boolean.TRUE.equals(s.notAssessable())
                    && !missing(s.text(), cited(s.evidence(), sources), inputs).isEmpty()) {
                unverified++;
            }
        }
        return new Result(new SkillOutput(List.copyOf(sections)), unverified);
    }

    /** The anchors of {@code text} found neither in the cited sources nor in the inputs; at most ten. */
    static List<String> missing(String text, String cited, String inputs) {
        Set<String> out = new LinkedHashSet<>();
        Matcher number = NUMBER.matcher(text);
        while (number.find()) {
            String digits = number.group().replaceAll("[^0-9]", "");
            if (digits.length() < 2) continue;
            if (!containsNumber(cited, digits) && !containsNumber(inputs, digits)) out.add(number.group());
        }
        Matcher code = CODE.matcher(text);
        while (code.find()) {
            String key = TurkishText.foldUpper(code.group());
            if (key.chars().allMatch(Character::isDigit)) continue;
            if (!cited.toUpperCase(java.util.Locale.ROOT).contains(key) && !inputs.toUpperCase(java.util.Locale.ROOT).contains(key)) {
                out.add(code.group());
            }
        }
        return out.stream().limit(MAX_LISTED).toList();
    }

    private static String cited(List<Integer> evidence, List<Source> sources) {
        if (evidence == null) return "";
        StringBuilder text = new StringBuilder();
        for (Integer n : evidence) {
            if (n != null && n >= 1 && n <= sources.size()) text.append(sources.get(n - 1).text()).append('\n');
        }
        return digitsAndFolded(text.toString());
    }

    /**
     * The text folded (codes compare case- and diacritic-insensitively), plus a line of every number's digits, so a
     * number is found whatever separators the source used.
     */
    static String digitsAndFolded(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder numbers = new StringBuilder("\n");
        Matcher m = NUMBER.matcher(text);
        while (m.find()) numbers.append(' ').append(m.group().replaceAll("[^0-9]", "")).append(' ');
        return TurkishText.fold(text) + numbers;
    }

    private static boolean containsNumber(String haystack, String digits) {
        return haystack.contains(" " + digits + " ");
    }

    /** All text values of the inputs, for {@link #check}. */
    public static String inputsText(Map<String, Object> values) {
        StringBuilder all = new StringBuilder();
        values.values().forEach(v -> {
            if (v instanceof String s) all.append(s).append('\n');
        });
        return all.toString();
    }
}
