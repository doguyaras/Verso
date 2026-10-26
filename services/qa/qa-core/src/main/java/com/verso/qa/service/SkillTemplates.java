package com.verso.qa.service;

import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assembles a skill draft from verified answers and their cited passages (ADR-0053) with fixed templates, no model:
 *
 * <ul>
 *   <li>an answer written as a list ("1. …", "- …") gives one step per item; any other answer gives one step;</li>
 *   <li>a step is bound to the chunks of the pages its own [n] markers cite (all cited pages of its answer, at most
 *       {@value SkillRules#MAX_PAGES_PER_STEP}, when it has none); the markers are removed from the text;</li>
 *   <li>every step is checked against its sources with the groundedness check of ADR-0047: each number and code must
 *       stand in them ({@link #grounded});</li>
 *   <li>triggers are the answers' (verified, admin-worded) questions; golden cases their first cited page, with a key
 *       fact only when that page really says it.</li>
 * </ul>
 *
 * Pure: the texts come in, the draft goes out; nothing is logged.
 */
public final class SkillTemplates {

    private static final Pattern MARKER = Pattern.compile("\\s*\\[(\\d{1,3})]");
    private static final Pattern ITEM = Pattern.compile("^\\s*(?:\\d{1,2}[.)]|[-*•])\\s+(.*)$");
    private static final Pattern HIDDEN = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private SkillTemplates() {}

    /**
     * A verified answer to draft from.
     *
     * @param citations the answer's citation numbers and their pages
     * @param chunks    the chunks of those pages, read with the drafting admin's principals (text included)
     */
    public record SourceAnswer(String question, String answer, Map<Integer, SourcePage> citations,
                               List<SourceChunk> chunks, List<String> keyFacts) {

        @Override
        public String toString() {
            return "SourceAnswer[citations=" + citations.size() + ", chunks=" + chunks.size() + "]";
        }
    }

    /** A drafted step: its text, the chunks it is bound to, and whether its numbers and codes stand in them. */
    public record DraftStep(String instruction, List<SourceChunk> sources, boolean grounded) {

        @Override
        public String toString() {
            return "DraftStep[sources=" + sources.size() + ", grounded=" + grounded + "]";
        }
    }

    /** A golden case seed: the question, the page that must come back, an optional fact of that page. */
    public record GoldenSeed(String question, SourcePage page, String fact) {
    }

    /** The steps of the answers, in order, at most {@code maxSteps}, identical texts once. */
    public static List<DraftStep> steps(List<SourceAnswer> answers, int maxSteps, int maxChars) {
        List<DraftStep> steps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SourceAnswer answer : answers) {
            Map<SourcePage, List<SourceChunk>> byPage = new LinkedHashMap<>();
            answer.chunks().forEach(c -> byPage.computeIfAbsent(c.page(), p -> new ArrayList<>()).add(c));
            for (String segment : segments(answer.answer())) {
                if (steps.size() >= maxSteps) return steps;
                String text = clean(segment, maxChars);
                if (text.isEmpty() || !seen.add(ExcerptSelector.fold(text))) continue;
                List<SourceChunk> sources = sourcesOf(segment, answer.citations(), byPage);
                if (sources.isEmpty()) continue;
                steps.add(new DraftStep(text, sources, grounded(text, sources)));
            }
        }
        return steps;
    }

    /** The parts of an answer that become steps: its list items when it has two or more, else the whole answer. */
    static List<String> segments(String answer) {
        List<String> items = new ArrayList<>();
        StringBuilder current = null;
        for (String line : answer.split("\\R")) {
            Matcher item = ITEM.matcher(line);
            if (item.matches()) {
                if (current != null) items.add(current.toString());
                current = new StringBuilder(item.group(1));
            } else if (current != null && !line.isBlank()) {
                current.append(' ').append(line.strip());
            } else if (current != null) {
                items.add(current.toString());
                current = null;
            }
        }
        if (current != null) items.add(current.toString());
        return items.size() >= 2 ? items : List.of(answer);
    }

    /** The chunks a segment is bound to: those of the pages its markers cite, else every cited page of its answer. */
    private static List<SourceChunk> sourcesOf(String segment, Map<Integer, SourcePage> citations,
                                               Map<SourcePage, List<SourceChunk>> byPage) {
        Set<Integer> numbers = new TreeSet<>();
        Matcher marker = MARKER.matcher(segment);
        while (marker.find()) numbers.add(Integer.parseInt(marker.group(1)));
        Set<SourcePage> pages = new LinkedHashSet<>();
        for (Integer number : numbers) {
            SourcePage page = citations.get(number);
            if (page != null) pages.add(page);
        }
        if (pages.isEmpty()) {
            for (SourcePage page : citations.values()) {
                if (pages.size() >= SkillRules.MAX_PAGES_PER_STEP) break;
                pages.add(page);
            }
        }
        List<SourceChunk> sources = new ArrayList<>();
        for (SourcePage page : pages) sources.addAll(byPage.getOrDefault(page, List.of()));
        return sources;
    }

    /** The text of a step: markers and hidden characters removed, one line, cut at a word within {@code maxChars}. */
    static String clean(String segment, int maxChars) {
        String text = HIDDEN.matcher(MARKER.matcher(segment).replaceAll("")).replaceAll(" ")
                .replaceAll("\\s+", " ").strip();
        Matcher item = ITEM.matcher(text);
        if (item.matches()) text = item.group(1).strip();
        if (text.length() <= maxChars) return text;
        String cut = text.substring(0, maxChars - 1);
        int space = cut.lastIndexOf(' ');
        if (space > maxChars / 2) cut = cut.substring(0, space);
        return cut.strip() + "…";
    }

    /**
     * The groundedness check of ADR-0047 on one step: every number, number word and code it states stands in its
     * sources next to the words it stands next to. A step without any is grounded.
     */
    public static boolean grounded(String instruction, List<SourceChunk> sources) {
        List<String> texts = sources.stream().map(c -> c.content() == null ? "" : c.content()).toList();
        return !AnchorCheck.check(instruction, texts, null).unverified();
    }

    /** The triggers: the answers' questions, one line each, in order, distinct, at most {@value SkillRules#MAX_TRIGGERS}. */
    public static List<String> triggers(List<SourceAnswer> answers) {
        Map<String, String> distinct = new LinkedHashMap<>();
        for (SourceAnswer answer : answers) {
            String question = clean(answer.question(), 1000);
            if (!question.isEmpty()) distinct.putIfAbsent(ExcerptSelector.fold(question), question);
            if (distinct.size() >= SkillRules.MAX_TRIGGERS) break;
        }
        return List.copyOf(distinct.values());
    }

    /**
     * Golden case seeds: per answer its question and first cited page that has chunks, with its first key fact only
     * when that page states it; at most {@value SkillRules#MAX_GOLDEN}.
     */
    public static List<GoldenSeed> golden(List<SourceAnswer> answers) {
        List<GoldenSeed> seeds = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SourceAnswer answer : answers) {
            if (seeds.size() >= SkillRules.MAX_GOLDEN) break;
            String question = clean(answer.question(), 1000);
            if (question.isEmpty() || !seen.add(ExcerptSelector.fold(question))) continue;
            for (SourcePage page : answer.citations().values()) {
                List<SourceChunk> chunks = answer.chunks().stream().filter(c -> c.page().equals(page)).toList();
                if (chunks.isEmpty()) continue;
                String text = ExcerptSelector.fold(String.join("\n", chunks.stream()
                        .map(c -> c.content() == null ? "" : c.content()).toList()));
                String fact = answer.keyFacts().stream().map(String::strip)
                        .filter(f -> !f.isEmpty() && f.length() <= 200 && text.contains(ExcerptSelector.fold(f)))
                        .findFirst().orElse(null);
                seeds.add(new GoldenSeed(question, page, fact));
                break;
            }
        }
        return seeds;
    }

    /** The SKILL.md description: what it is and when to use it, one line, at most 1024 characters. */
    public static String description(List<String> triggers, int steps) {
        String topic = triggers.isEmpty() ? "Prosedür" : triggers.getFirst().replaceAll("[?？]+$", "").strip();
        StringBuilder text = new StringBuilder(topic).append(": ").append(steps).append(" adımlı onaylı prosedür.");
        if (!triggers.isEmpty()) {
            text.append(" Şu tür sorularda kullanın: ");
            for (int i = 0; i < triggers.size() && i < 3; i++) {
                if (i > 0) text.append("; ");
                text.append('"').append(triggers.get(i).replace('"', '\'')).append('"');
            }
            text.append('.');
        }
        String line = HIDDEN.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").strip();
        return line.length() <= SkillRules.DESCRIPTION_MAX ? line
                : line.substring(0, SkillRules.DESCRIPTION_MAX - 1).strip() + "…";
    }
}
