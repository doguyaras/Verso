package com.verso.qa.service;

import com.verso.document.api.dto.DocumentChange;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.qa.api.dto.SkillStaleReason;
import com.verso.qa.api.dto.SkillState;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The rules of skills (ADR-0053) without I/O: the state machine, the scope (the intersection every reader must reach),
 * the hash binding, the approval checks, the name and the "how do I" test. Approving and archiving are human; the
 * system only makes a skill STALE.
 */
public final class SkillRules {

    /** agentskills.io: a name of at most 64 lower-case letters, digits and single hyphens. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    public static final int NAME_MAX = 64;
    public static final int MAX_STEPS = 12;
    public static final int MAX_TRIGGERS = 10;
    /** ADR-0048: every skill ships with 2-5 golden cases. */
    public static final int MIN_GOLDEN = 2;
    public static final int MAX_GOLDEN = 5;
    public static final int DESCRIPTION_MAX = 1024;
    /** A step without its own markers is bound to at most this many pages of its answer. */
    public static final int MAX_PAGES_PER_STEP = 10;

    // "How do I …" in Turkish and English, matched on folded text (no diacritics): a likely procedure.
    private static final Pattern PROCEDURAL = Pattern.compile(
            "\\b(nasil|ne sekilde|hangi adim|adim|adimlar|basvur\\w*|kurul\\w*|kurulum\\w*|yapil\\w*|yaparim|yapabilirim"
                    + "|talep\\w*|olustur\\w*|tanimla\\w*|surec\\w*|prosedur\\w*|how( do| to| can)?|steps?)\\b");

    private SkillRules() {}

    // ---------------------------------------------------------------- state machine

    /** Only a DRAFT is approved; anything else is refused (STALE needs a new draft, APPROVED is already). */
    public static boolean canApprove(SkillState state) {
        return state == SkillState.DRAFT;
    }

    /** The state after an approval that brought the cycle to {@code approvals} distinct approvers. */
    public static SkillState afterApproval(int approvals, int required) {
        return approvals >= required ? SkillState.APPROVED : SkillState.DRAFT;
    }

    /** A new version: of a DRAFT, or of an APPROVED skill (which goes back to DRAFT until approved again). */
    public static boolean canEdit(SkillState state) {
        return state == SkillState.DRAFT || state == SkillState.APPROVED;
    }

    /** The system may make a draft or an approved skill stale; stale and archived stay as they are. */
    public static boolean canGoStale(SkillState state) {
        return state == SkillState.DRAFT || state == SkillState.APPROVED;
    }

    public static boolean canArchive(SkillState state) {
        return state != SkillState.ARCHIVED;
    }

    /** Only an APPROVED skill is served, exported or listed over MCP. */
    public static boolean servable(SkillState state) {
        return state == SkillState.APPROVED;
    }

    public static SkillStaleReason staleReason(DocumentChange.Kind kind) {
        return switch (kind) {
            case DELETED -> SkillStaleReason.SOURCE_DELETED;
            case REPLACED -> SkillStaleReason.SOURCE_REPLACED;
            case MOVED -> SkillStaleReason.SOURCE_MOVED;
        };
    }

    // ---------------------------------------------------------------- scope

    /** Why a skill cannot be made from these sources. */
    public enum ScopeProblem {
        /** No source at all, or a bound page without any chunk the admin reads now. */
        SOURCE_UNAVAILABLE,
        /** A source is a personal document: procedures are shared knowledge (collections only). */
        PERSONAL_SOURCE
    }

    /** The collections of a skill (every reader must reach all of them), or the reason it has none. */
    public record Scope(List<UUID> collections, ScopeProblem problem) {

        static Scope refused(ScopeProblem problem) {
            return new Scope(List.of(), problem);
        }

        public Optional<ScopeProblem> refusal() {
            return Optional.ofNullable(problem);
        }
    }

    /**
     * The required collection scope: every collection of every bound chunk, in order. A caller sees the skill only when
     * they reach all of them, so its audience is the intersection of the collections' audiences. A personal source or
     * an empty binding refuses.
     */
    public static Scope scope(Collection<SourceChunk> sources) {
        if (sources.isEmpty()) return Scope.refused(ScopeProblem.SOURCE_UNAVAILABLE);
        Set<UUID> collections = new LinkedHashSet<>();
        for (SourceChunk chunk : sources) {
            if (chunk.collectionId() == null) return Scope.refused(ScopeProblem.PERSONAL_SOURCE);
            collections.add(chunk.collectionId());
        }
        return new Scope(List.copyOf(collections), null);
    }

    /** The intersection rule: a reader who reaches some but not all of the scope's collections does not read it. */
    public static boolean readableBy(Collection<UUID> scope, Collection<UUID> readerCollections) {
        return !scope.isEmpty() && readerCollections.containsAll(scope);
    }

    // ---------------------------------------------------------------- hash binding

    /**
     * Every bound (page, hash) is found in what the reader reads now: matched by text, not chunk id, like the cards
     * (ADR-0049). An empty binding is broken.
     */
    public static boolean intact(Collection<Binding> bound, Collection<SourceChunk> current) {
        return KnowledgeCardRules.check(bound, current) == KnowledgeCardRules.BindingState.INTACT;
    }

    /** The pages of a binding, in order. */
    public static Set<SourcePage> pages(Collection<Binding> bound) {
        Set<SourcePage> pages = new LinkedHashSet<>();
        bound.forEach(b -> pages.add(b.page()));
        return pages;
    }

    // ---------------------------------------------------------------- approval

    /** One golden case as approval checks it: the expected page and the optional fact. */
    public record GoldenCheck(SourcePage page, String fact) {
    }

    /**
     * What keeps a version from approval, in a fixed order; empty when it may be approved. {@code pageText} is the
     * approver's reading of a bound page (null: not readable).
     *
     * @return GOLDEN_COUNT, NO_TRIGGER, NO_STEP, STEP_UNGROUNDED, GOLDEN_PAGE (the expected page is not a source of the
     *         skill) or GOLDEN_FACT (the fact is not on that page)
     */
    public static List<String> approvalProblems(int steps, List<Boolean> grounded, int triggers,
                                                List<GoldenCheck> golden, Set<SourcePage> boundPages,
                                                java.util.function.Function<SourcePage, String> pageText) {
        List<String> problems = new ArrayList<>();
        if (golden.size() < MIN_GOLDEN || golden.size() > MAX_GOLDEN) problems.add("GOLDEN_COUNT");
        if (triggers < 1) problems.add("NO_TRIGGER");
        if (steps < 1) problems.add("NO_STEP");
        if (grounded.contains(Boolean.FALSE)) problems.add("STEP_UNGROUNDED");
        for (GoldenCheck check : golden) {
            if (!boundPages.contains(check.page())) {
                problems.add("GOLDEN_PAGE");
                break;
            }
        }
        if (!problems.contains("GOLDEN_PAGE")) {
            for (GoldenCheck check : golden) {
                if (check.fact() == null) continue;
                String text = pageText.apply(check.page());
                if (text == null || !ExcerptSelector.fold(text).contains(ExcerptSelector.fold(check.fact().strip()))) {
                    problems.add("GOLDEN_FACT");
                    break;
                }
            }
        }
        return List.copyOf(problems);
    }

    // ---------------------------------------------------------------- names and kinds

    /**
     * A spec-valid name from free text: Turkish letters folded, everything else a hyphen, at most 64 characters cut at
     * a hyphen; "prosedur" when nothing is left.
     */
    public static String slug(String text) {
        String folded = text == null ? "" : ExcerptSelector.fold(text);
        String slug = folded.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > NAME_MAX) {
            slug = slug.substring(0, NAME_MAX);
            int cut = slug.lastIndexOf('-');
            if (cut > 0) slug = slug.substring(0, cut);
            slug = slug.replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "prosedur" : slug;
    }

    /** {@code name-2}, {@code name-3} … still within 64 characters. */
    public static String withSuffix(String name, int n) {
        if (n <= 1) return name;
        String suffix = "-" + n;
        String base = name.length() + suffix.length() > NAME_MAX ? name.substring(0, NAME_MAX - suffix.length()) : name;
        return base.replaceAll("-+$", "") + suffix;
    }

    public static boolean validName(String name) {
        return name != null && name.length() <= NAME_MAX && NAME.matcher(name).matches();
    }

    /** Whether a (verified, admin-worded) question asks how to do something. */
    public static boolean procedural(String question) {
        return question != null && PROCEDURAL.matcher(ExcerptSelector.fold(question)).find();
    }
}
