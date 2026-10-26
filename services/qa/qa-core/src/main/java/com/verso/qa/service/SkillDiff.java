package com.verso.qa.service;

import com.verso.qa.api.dto.SkillVersionDiff;
import com.verso.qa.api.dto.SkillVersionDiff.Change;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The field-by-field difference of two skill versions (ADR-0053), without I/O. */
public final class SkillDiff {

    private SkillDiff() {}

    /** What a version says, as the diff compares it. */
    public record Snapshot(int version, String name, String description, List<String> steps, List<String> triggers,
                           List<String> goldenCases) {

        @Override
        public String toString() {
            return "Snapshot[version=" + version + "]";
        }
    }

    /**
     * Name, description, then steps, triggers and golden cases by position: CHANGED when both have the position and
     * differ, ADDED or REMOVED when only one has it. {@code withText} false leaves every text out (an admin who cannot
     * read the sources sees which fields changed, not how).
     */
    public static SkillVersionDiff diff(Snapshot from, Snapshot to, boolean withText) {
        List<Change> changes = new ArrayList<>();
        field(changes, "name", 0, from.name(), to.name(), true);
        field(changes, "description", 0, from.description(), to.description(), withText);
        list(changes, "step", from.steps(), to.steps(), withText);
        list(changes, "trigger", from.triggers(), to.triggers(), withText);
        list(changes, "goldenCase", from.goldenCases(), to.goldenCases(), withText);
        return new SkillVersionDiff(from.version(), to.version(), List.copyOf(changes));
    }

    private static void list(List<Change> changes, String field, List<String> before, List<String> after,
                             boolean withText) {
        for (int i = 0; i < Math.max(before.size(), after.size()); i++) {
            field(changes, field, i + 1, i < before.size() ? before.get(i) : null, i < after.size() ? after.get(i) : null,
                    withText);
        }
    }

    private static void field(List<Change> changes, String field, int position, String before, String after,
                              boolean withText) {
        if (Objects.equals(before, after)) return;
        String kind = before == null ? "ADDED" : after == null ? "REMOVED" : "CHANGED";
        changes.add(new Change(field, position, kind, withText ? before : null, withText ? after : null));
    }
}
